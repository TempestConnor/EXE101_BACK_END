package com.exe101.commission.repository;

import com.exe101.artist.repository.ArtistRepository;
import com.exe101.commission.model.Commission;
import com.exe101.commission.model.CommissionDeliverable;
import com.exe101.commission.model.CommissionRequest;
import com.exe101.commission.model.DeliverableSubmission;
import com.exe101.commission.model.FileUpload;
import com.exe101.commission.model.QuoteTerms;
import com.exe101.commission.model.RevisionRequest;
import com.exe101.commission.service.CommissionDeliveryService;
import com.exe101.commission.service.CommissionQuoteService;
import com.exe101.commission.service.CommissionService;
import com.exe101.common.error.AppErrorMessage;
import com.exe101.common.error.AppException;
import com.exe101.customer.model.CustomerRegistration;
import com.exe101.customer.service.CustomerService;
import com.exe101.entity.ArtistProfile;
import com.exe101.entity.StaffAccount;
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

// Submitted versions and revision requests can never be changed or deleted (TR_Deliverable_Immutable and its
// siblings), so this runs only against the disposable test database selected by TEST_DB_URL and leaves its rows there.
// Each test uses new Users and Artists. The Commission is moved to IN_PROGRESS directly instead of through a payment:
// no Payment, proceeds or ledger row is written, so the database stays reconcilable.
@SpringBootTest(properties = {"app.orders.expiry-interval=PT1H", "app.fulfillment.grant-interval=PT1H"})
@EnabledIfEnvironmentVariable(named = "TEST_DB_URL", matches = ".+")
class CommissionDeliveryPersistenceTest {
    private static long blockedMillis = 500;
    private static long waitSeconds = 30;
    @Autowired
    private CommissionService commissionService;
    @Autowired
    private CommissionQuoteService quoteService;
    @Autowired
    private CommissionDeliveryService deliveryService;
    @Autowired
    private CustomerService customerService;
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
    void two_versions_numbered_1_and_2_ok() {
        // given
        var parties = inProgressCommission();
        var first = deliveryService.submit(parties.artistUserId(), parties.commissionId(), submission("cat.png"));
        deliveryService.requestRevision(parties.customerId(), parties.commissionId(), first.getDeliverableId(),
            "Make the cat orange");

        // when
        var second = deliveryService.submit(parties.artistUserId(), parties.commissionId(), submission("cat-v2.png"));
        var versions = deliveryService.list(parties.customerId(), parties.commissionId());

        // then
        assertAll(
            () -> assertThat(versions).extracting(CommissionDeliverable::getVersionNo).containsExactly(1, 2),
            () -> assertThat(versions.get(0).getFiles()).extracting("originalName").containsExactly("cat.png"),
            () -> assertThat(versions.get(0).getRevisionRequests()).extracting(RevisionRequest::getDetails)
                .containsExactly("Make the cat orange"),
            () -> assertThat(versions.get(1).getDeliverableId()).isEqualTo(second.getDeliverableId()),
            () -> assertThat(versions.get(1).getFiles()).extracting("originalName").containsExactly("cat-v2.png"),
            () -> assertThat(versions.get(1).getRevisionRequests()).isEmpty(),
            () -> assertThat(count("SELECT COUNT(*) FROM dbo.DeliverableFile WHERE DeliverableId = ?",
                first.getDeliverableId())).isEqualTo(1),
            () -> assertThat(jdbc.queryForObject("SELECT f.AccessClass FROM dbo.DeliverableFile d JOIN dbo.StoredFile f "
                + "ON f.FileId = d.FileId WHERE d.DeliverableId = ?", String.class, second.getDeliverableId()))
                .isEqualTo("COMMISSION"),
            () -> assertThat(commissionStatus(parties.commissionId())).isEqualTo("SUBMITTED_FOR_REVIEW"),
            () -> assertThat(events(parties.commissionId())).containsExactly("REQUESTED", "QUOTE_OFFERED",
                "QUOTE_ACCEPTED", "DELIVERABLE_SUBMITTED", "REVISION_REQUESTED", "DELIVERABLE_SUBMITTED")
        );
    }

    @Test
    void concurrent_approve_and_revision_one_winner_ok() throws Exception {
        // given
        var parties = inProgressCommission();
        var submitted = deliveryService.submit(parties.artistUserId(), parties.commissionId(), submission("cat.png"));
        var approved = new CountDownLatch(1);
        var release = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(2)) {
            // when
            // The approval is held uncommitted while the Customer also asks for a revision of the same version.
            Future<Commission> approval = executor.submit(() -> inTransaction(() -> {
                var outcome = deliveryService.approve(parties.customerId(), parties.commissionId(),
                    submitted.getDeliverableId());
                approved.countDown();
                release.await(waitSeconds, TimeUnit.SECONDS);
                return outcome;
            }));
            approved.await(waitSeconds, TimeUnit.SECONDS);
            Future<RevisionRequest> revision = executor.submit(() -> deliveryService.requestRevision(
                parties.customerId(), parties.commissionId(), submitted.getDeliverableId(), "Make it orange"));
            var revisionWaited = isBlocked(revision);
            release.countDown();
            approval.get(waitSeconds, TimeUnit.SECONDS);

            // then
            assertAll(
                () -> assertThat(revisionWaited).isTrue(),
                () -> assertThatThrownBy(() -> revision.get(waitSeconds, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .cause()
                    .isInstanceOf(AppException.class)
                    .extracting("error")
                    .isEqualTo(AppErrorMessage.COMMISSION_ACTION_NOT_ALLOWED),
                () -> assertThat(commissionStatus(parties.commissionId())).isEqualTo("AWAITING_PAYMENT"),
                () -> assertThat(count("SELECT COUNT(*) FROM dbo.RevisionRequest WHERE CommissionId = ?",
                    parties.commissionId())).isZero(),
                () -> assertThat(count("SELECT COUNT(*) FROM dbo.Payment p JOIN dbo.CommissionQuote q "
                    + "ON q.QuoteId = p.QuoteId WHERE q.CommissionId = ?", parties.commissionId())).isZero(),
                () -> assertThat(events(parties.commissionId())).endsWith("DELIVERABLE_SUBMITTED",
                    "DELIVERABLE_APPROVED")
            );
        }
    }

    @Test
    void deliverable_update_rejected_ko() {
        // given
        var parties = inProgressCommission();
        var submitted = deliveryService.submit(parties.artistUserId(), parties.commissionId(), submission("cat.png"));
        var revision = deliveryService.requestRevision(parties.customerId(), parties.commissionId(),
            submitted.getDeliverableId(), "Make the cat orange");

        // when / then
        assertAll(
            () -> assertThatThrownBy(() -> jdbc.update(
                    "UPDATE dbo.CommissionDeliverable SET Notes = N'changed' WHERE DeliverableId = ?",
                    submitted.getDeliverableId()))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("Submitted Commission versions are immutable."),
            () -> assertThatThrownBy(() -> jdbc.update(
                    "DELETE FROM dbo.DeliverableFile WHERE DeliverableId = ?", submitted.getDeliverableId()))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("Files of a submitted Commission version are immutable."),
            () -> assertThatThrownBy(() -> jdbc.update(
                    "UPDATE dbo.RevisionRequest SET Details = N'changed' WHERE RevisionId = ?",
                    revision.getRevisionId()))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("Revision requests are immutable."),
            () -> assertThat(jdbc.queryForObject("SELECT Details FROM dbo.RevisionRequest WHERE RevisionId = ?",
                String.class, revision.getRevisionId())).isEqualTo("Make the cat orange"),
            () -> assertThat(count("SELECT COUNT(*) FROM dbo.DeliverableFile WHERE DeliverableId = ?",
                submitted.getDeliverableId())).isEqualTo(1)
        );
    }

    private Parties inProgressCommission() {
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
        var offered = quoteService.offer(artistUserId, commission.getCommissionId(), QuoteTerms.builder()
            .withPrice(new BigDecimal("1500000"))
            .withScopeText("Full-body portrait, one character")
            .withExpectedCompletionTerms("Within 3 weeks of the first payment")
            .build());
        quoteService.accept(customerId, commission.getCommissionId(), offered.getQuoteId());
        jdbc.update("UPDATE dbo.Commission SET Status = 'IN_PROGRESS' WHERE CommissionId = ?",
            commission.getCommissionId());
        return new Parties(customerId, artistUserId, commission.getCommissionId());
    }

    private DeliverableSubmission submission(String fileName) {
        return DeliverableSubmission.builder()
            .withNotes("Final render")
            .withUploads(List.of(FileUpload.builder()
                .withOriginalName(fileName)
                .withBytes(new byte[] {1, 2, 3})
                .build()))
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
            "f18-test-" + UUID.randomUUID() + "@example.invalid", "long-password", "Member")).getUserId();
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
            .orElseGet(() -> staffRepository.saveAndFlush(new StaffAccount("f18-test-" + UUID.randomUUID(),
                "unused".getBytes(StandardCharsets.UTF_8), "ADMIN", true, now())));
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(ZoneOffset.UTC).withNano(0);
    }

    private record Parties(Long customerId, Long artistUserId, Long commissionId) {
    }
}
