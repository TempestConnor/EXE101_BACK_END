package com.exe101.commission.repository;

import com.exe101.artist.repository.ArtistRepository;
import com.exe101.commission.model.Commission;
import com.exe101.commission.model.CommissionQuote;
import com.exe101.commission.model.CommissionRequest;
import com.exe101.commission.model.QuoteTerms;
import com.exe101.commission.service.CommissionQuoteService;
import com.exe101.commission.service.CommissionService;
import com.exe101.common.error.AppErrorMessage;
import com.exe101.common.error.AppException;
import com.exe101.customer.model.CustomerRegistration;
import com.exe101.customer.service.CustomerService;
import com.exe101.entity.ArtistProfile;
import com.exe101.entity.StaffAccount;
import com.exe101.ledger.model.Mismatch;
import com.exe101.ledger.service.LedgerService;
import com.exe101.staff.repository.StaffRepository;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertAll;

// Quotes can never be deleted (TR_Quote_Terms_Immutable), so their Commissions cannot be removed either. This runs
// only against the disposable test database (database/test/reset_test_database.sql), selected by TEST_DB_URL, and
// leaves its rows there. Each test uses new Users and Artists. No money moves, so the database stays reconcilable.
// The scheduled jobs are slowed down so they do not run during a test.
@SpringBootTest(properties = {"app.orders.expiry-interval=PT1H", "app.fulfillment.grant-interval=PT1H",
    "app.checkout.tax-rate=0.10"})
@EnabledIfEnvironmentVariable(named = "TEST_DB_URL", matches = ".+")
class CommissionQuotePersistenceTest {
    private static long blockedMillis = 500;
    private static long waitSeconds = 30;
    @Autowired
    private CommissionService commissionService;
    @Autowired
    private CommissionQuoteService quoteService;
    @Autowired
    private CustomerService customerService;
    @Autowired
    private LedgerService ledgerService;
    @Autowired
    private ArtistRepository artistRepository;
    @Autowired
    private StaffRepository staffRepository;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PlatformTransactionManager transactionManager;
    private Long adminId;

    @DynamicPropertySource
    static void testDatabase(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv("TEST_DB_URL"));
    }

    @BeforeEach
    void setUp() {
        adminId = admin().getStaffId();
    }

    @Test
    void accept_quote_persists_snapshot_ok() {
        // given
        var parties = newCommission();
        var offered = quoteService.offer(parties.artistUserId(), parties.commissionId(), terms());

        // when
        quoteService.accept(parties.customerId(), parties.commissionId(), offered.getQuoteId());
        var quote = jdbc.queryForMap("SELECT Status, Price, TaxAmount, FeeRate, FeeAmount, ArtistProceeds, AcceptedAt, "
            + "PaidAt FROM dbo.CommissionQuote WHERE QuoteId = ?", offered.getQuoteId());
        var reconciliation = ledgerService.reconcile();

        // then
        assertAll(
            () -> assertThat(quote.get("Status")).isEqualTo("ACCEPTED"),
            () -> assertThat((BigDecimal) quote.get("Price")).isEqualByComparingTo("1500000"),
            () -> assertThat((BigDecimal) quote.get("TaxAmount")).isEqualByComparingTo("150000"),
            () -> assertThat((BigDecimal) quote.get("FeeRate")).isEqualByComparingTo("0"),
            () -> assertThat((BigDecimal) quote.get("FeeAmount")).isEqualByComparingTo("0"),
            () -> assertThat((BigDecimal) quote.get("ArtistProceeds")).isEqualByComparingTo("1500000"),
            () -> assertThat(quote.get("AcceptedAt")).isNotNull(),
            () -> assertThat(quote.get("PaidAt")).isNull(),
            () -> assertThat(commissionStatus(parties.commissionId())).isEqualTo("AWAITING_PAYMENT"),
            () -> assertThat(count("SELECT COUNT(*) FROM dbo.Payment WHERE QuoteId = ?", offered.getQuoteId()))
                .isZero(),
            () -> assertThat(count("SELECT COUNT(*) FROM dbo.ArtistProceeds WHERE QuoteId = ?", offered.getQuoteId()))
                .isZero(),
            () -> assertThat(events(parties.commissionId()))
                .containsExactly("REQUESTED", "QUOTE_OFFERED", "QUOTE_ACCEPTED"),
            () -> assertThat(reconciliation.getMismatches()).extracting(Mismatch::getRecordId)
                .doesNotContain(offered.getQuoteId(), parties.commissionId())
        );
    }

    @Test
    void accepted_quote_terms_immutable_ok() {
        // given
        var parties = newCommission();
        var offered = quoteService.offer(parties.artistUserId(), parties.commissionId(), terms());
        quoteService.accept(parties.customerId(), parties.commissionId(), offered.getQuoteId());

        // when / then
        // Price and proceeds change together, so only the trigger (not the proceeds CHECK) can reject the update.
        assertAll(
            () -> assertThatThrownBy(() -> jdbc.update(
                    "UPDATE dbo.CommissionQuote SET Price = 1, ArtistProceeds = 1 WHERE QuoteId = ?",
                    offered.getQuoteId()))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("Accepted quote terms are immutable."),
            () -> assertThatThrownBy(() -> jdbc.update(
                    "DELETE FROM dbo.CommissionQuote WHERE QuoteId = ?", offered.getQuoteId()))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("Quotes cannot be deleted."),
            () -> assertThat(jdbc.queryForObject("SELECT Price FROM dbo.CommissionQuote WHERE QuoteId = ?",
                BigDecimal.class, offered.getQuoteId())).isEqualByComparingTo("1500000")
        );
    }

    @Test
    void accept_and_withdraw_race_one_wins_ok() throws Exception {
        // given
        var parties = newCommission();
        var offered = quoteService.offer(parties.artistUserId(), parties.commissionId(), terms());
        var accepted = new CountDownLatch(1);
        var release = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(2)) {
            // when
            // The acceptance is held uncommitted while the Artist withdraws the same Quote.
            Future<CommissionQuote> acceptance = executor.submit(() -> inTransaction(() -> {
                var outcome = quoteService.accept(parties.customerId(), parties.commissionId(),
                    offered.getQuoteId());
                accepted.countDown();
                release.await(waitSeconds, TimeUnit.SECONDS);
                return outcome;
            }));
            accepted.await(waitSeconds, TimeUnit.SECONDS);
            Future<CommissionQuote> withdrawal = executor.submit(() ->
                quoteService.withdraw(parties.artistUserId(), parties.commissionId(), offered.getQuoteId()));
            var withdrawalWaited = isBlocked(withdrawal);
            release.countDown();
            acceptance.get(waitSeconds, TimeUnit.SECONDS);

            // then
            assertAll(
                () -> assertThat(withdrawalWaited).isTrue(),
                () -> assertThatThrownBy(() -> withdrawal.get(waitSeconds, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .cause()
                    .isInstanceOf(AppException.class)
                    .extracting("error")
                    .isEqualTo(AppErrorMessage.QUOTE_NOT_OFFERED),
                () -> assertThat(jdbc.queryForObject("SELECT Status FROM dbo.CommissionQuote WHERE QuoteId = ?",
                    String.class, offered.getQuoteId())).isEqualTo("ACCEPTED"),
                () -> assertThat(commissionStatus(parties.commissionId())).isEqualTo("AWAITING_PAYMENT"),
                () -> assertThat(events(parties.commissionId()))
                    .containsExactly("REQUESTED", "QUOTE_OFFERED", "QUOTE_ACCEPTED")
            );
        }
    }

    @Test
    void concurrent_offers_one_outstanding_ok() throws Exception {
        // given
        var parties = newCommission();
        var offeredOnce = new CountDownLatch(1);
        var release = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(2)) {
            // when
            // The first offer is held uncommitted while a second offer arrives for the same Commission.
            Future<CommissionQuote> first = executor.submit(() -> inTransaction(() -> {
                var outcome = quoteService.offer(parties.artistUserId(), parties.commissionId(), terms());
                offeredOnce.countDown();
                release.await(waitSeconds, TimeUnit.SECONDS);
                return outcome;
            }));
            offeredOnce.await(waitSeconds, TimeUnit.SECONDS);
            Future<CommissionQuote> second = executor.submit(() ->
                quoteService.offer(parties.artistUserId(), parties.commissionId(), terms()));
            var secondWaited = isBlocked(second);
            release.countDown();
            first.get(waitSeconds, TimeUnit.SECONDS);

            // then
            assertAll(
                () -> assertThat(secondWaited).isTrue(),
                () -> assertThatThrownBy(() -> second.get(waitSeconds, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .cause()
                    .isInstanceOf(AppException.class)
                    .extracting("error")
                    .isEqualTo(AppErrorMessage.QUOTE_ALREADY_OFFERED),
                () -> assertThat(count("SELECT COUNT(*) FROM dbo.CommissionQuote WHERE CommissionId = ? "
                    + "AND Status = 'OFFERED'", parties.commissionId())).isEqualTo(1),
                () -> assertThat(commissionStatus(parties.commissionId())).isEqualTo("QUOTED")
            );
        }
    }

    private Parties newCommission() {
        var customerId = newUser();
        var artistUserId = newUser();
        var artist = artistRepository.saveAndFlush(new ArtistProfile(artistUserId, "Painter", "ACTIVE", true, adminId,
            now(), null));
        Commission commission = commissionService.request(customerId, CommissionRequest.builder()
            .withArtistId(artist.getArtistId())
            .withDescription("Portrait of my cat")
            .withAllowPublicDisplay(false)
            .withUploads(List.of())
            .build());
        return new Parties(customerId, artistUserId, commission.getCommissionId());
    }

    private QuoteTerms terms() {
        return QuoteTerms.builder()
            .withPrice(new BigDecimal("1500000"))
            .withScopeText("Full-body portrait, one character")
            .withExpectedCompletionTerms("Within 3 weeks of the first payment")
            .build();
    }

    private String commissionStatus(Long commissionId) {
        return jdbc.queryForObject("SELECT Status FROM dbo.Commission WHERE CommissionId = ?", String.class,
            commissionId);
    }

    private List<String> events(Long commissionId) {
        return jdbc.queryForList("SELECT EventType FROM dbo.CommissionEvent WHERE CommissionId = ? ORDER BY EventId",
            String.class, commissionId);
    }

    private Integer count(String sql, Long id) {
        return jdbc.queryForObject(sql, Integer.class, id);
    }

    private Long newUser() {
        return customerService.register(new CustomerRegistration(
            "f17-test-" + UUID.randomUUID() + "@example.invalid", "long-password", "Member")).getUserId();
    }

    private <T> T inTransaction(Callable<T> work) {
        return new TransactionTemplate(transactionManager).execute(status -> {
            try {
                return work.call();
            } catch (RuntimeException exception) {
                throw exception;
            } catch (Exception exception) {
                throw new IllegalStateException(exception);
            }
        });
    }

    // True when the work is still waiting for a lock after a short time.
    private boolean isBlocked(Future<?> work) throws Exception {
        try {
            work.get(blockedMillis, TimeUnit.MILLISECONDS);
            return false;
        } catch (TimeoutException exception) {
            return true;
        } catch (ExecutionException exception) {
            return false;
        }
    }

    private StaffAccount admin() {
        return staffRepository.findAll().stream()
            .filter(staff -> "ADMIN".equals(staff.getRole()) && staff.isIsActive())
            .findFirst()
            .orElseGet(() -> staffRepository.saveAndFlush(new StaffAccount("f17-test-" + UUID.randomUUID(),
                "unused".getBytes(StandardCharsets.UTF_8), "ADMIN", true, now())));
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(ZoneOffset.UTC).withNano(0);
    }

    private record Parties(Long customerId, Long artistUserId, Long commissionId) {
    }
}
