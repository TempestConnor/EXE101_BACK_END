package com.exe101.commission.repository;

import com.exe101.artist.repository.ArtistRepository;
import com.exe101.artist.service.ArtistService;
import com.exe101.commission.model.Commission;
import com.exe101.commission.model.CommissionRequest;
import com.exe101.commission.service.CommissionService;
import com.exe101.common.error.AppErrorMessage;
import com.exe101.common.error.AppException;
import com.exe101.customer.model.CustomerRegistration;
import com.exe101.customer.service.CustomerService;
import com.exe101.entity.ArtistProfile;
import com.exe101.entity.StaffAccount;
import com.exe101.staff.repository.StaffRepository;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
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

// Sent messages can never be deleted (TR_Message_Immutable), so their Commissions cannot be removed either. This
// runs only against the disposable test database (database/test/reset_test_database.sql), selected by TEST_DB_URL,
// and leaves its rows there. Each test uses new Users and Artists. The scheduled jobs are slowed down so they do not
// run during a test.
@SpringBootTest(properties = {"app.orders.expiry-interval=PT1H", "app.fulfillment.grant-interval=PT1H"})
@EnabledIfEnvironmentVariable(named = "TEST_DB_URL", matches = ".+")
class CommissionPersistenceTest {
    private static long blockedMillis = 500;
    private static long waitSeconds = 30;
    @Autowired
    private CommissionService commissionService;
    @Autowired
    private ArtistService artistService;
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
    void message_update_rejected_by_database_ko() {
        // given
        var customerId = newUser();
        var commission = commissionService.request(customerId, request(newArtist().getArtistId()));
        var message = commissionService.sendMessage(customerId, commission.getCommissionId(), "Original text");

        // when / then
        assertAll(
            () -> assertThatThrownBy(() -> jdbc.update(
                    "UPDATE dbo.CommissionMessage SET Body = N'Edited' WHERE MessageId = ?", message.getMessageId()))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("Sent messages are immutable."),
            () -> assertThatThrownBy(() -> jdbc.update(
                    "DELETE FROM dbo.CommissionMessage WHERE MessageId = ?", message.getMessageId()))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("Sent messages are immutable."),
            () -> assertThat(jdbc.queryForObject("SELECT Body FROM dbo.CommissionMessage WHERE MessageId = ?",
                String.class, message.getMessageId())).isEqualTo("Original text")
        );
    }

    @Test
    void create_request_writes_event_ok() {
        // given
        var customerId = newUser();
        var artist = newArtist();

        // when
        var commission = commissionService.request(customerId, request(artist.getArtistId()));
        var row = jdbc.queryForMap("SELECT Status, CustomerId, ArtistId, AllowPublicDisplay FROM dbo.Commission "
            + "WHERE CommissionId = ?", commission.getCommissionId());
        var events = events(commission.getCommissionId());

        // then
        assertAll(
            () -> assertThat(row.get("Status")).isEqualTo("REQUESTED"),
            () -> assertThat(row.get("CustomerId")).isEqualTo(customerId),
            () -> assertThat(row.get("ArtistId")).isEqualTo(artist.getArtistId()),
            () -> assertThat(row.get("AllowPublicDisplay")).isEqualTo(true),
            () -> assertThat(events).singleElement().satisfies(event -> {
                assertThat(event.get("EventType")).isEqualTo("REQUESTED");
                assertThat(event.get("FromStatus")).isNull();
                assertThat(event.get("ToStatus")).isEqualTo("REQUESTED");
                assertThat(event.get("ActorUserId")).isEqualTo(customerId);
            })
        );
    }

    @Test
    void create_request_after_deactivation_ko() throws Exception {
        // given
        var customerId = newUser();
        var artistId = newArtist().getArtistId();
        var deactivated = new CountDownLatch(1);
        var release = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(2)) {
            // when
            // The deactivation is held uncommitted while a request for the same Artist arrives.
            Future<Object> deactivation = executor.submit(() -> inTransaction(() -> {
                var outcome = artistService.deactivate(adminId, artistId, "Left the team");
                deactivated.countDown();
                release.await(waitSeconds, TimeUnit.SECONDS);
                return outcome;
            }));
            deactivated.await(waitSeconds, TimeUnit.SECONDS);
            Future<Commission> requested = executor.submit(() ->
                commissionService.request(customerId, request(artistId)));
            var requestWaited = isBlocked(requested);
            release.countDown();
            deactivation.get(waitSeconds, TimeUnit.SECONDS);

            // then
            assertAll(
                () -> assertThat(requestWaited).isTrue(),
                () -> assertThatThrownBy(() -> requested.get(waitSeconds, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .cause()
                    .isInstanceOf(AppException.class)
                    .extracting("error")
                    .isEqualTo(AppErrorMessage.ARTIST_NOT_ACTIVE),
                () -> assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM dbo.Commission WHERE ArtistId = ?",
                    Integer.class, artistId)).isZero()
            );
        }
    }

    private List<Map<String, Object>> events(Long commissionId) {
        return jdbc.queryForList("SELECT EventType, FromStatus, ToStatus, ActorUserId FROM dbo.CommissionEvent "
            + "WHERE CommissionId = ? ORDER BY EventId", commissionId);
    }

    private CommissionRequest request(Long artistId) {
        return CommissionRequest.builder()
            .withArtistId(artistId)
            .withDescription("Portrait of my cat")
            .withAllowPublicDisplay(true)
            .withUploads(List.of())
            .build();
    }

    private ArtistProfile newArtist() {
        return artistRepository.saveAndFlush(new ArtistProfile(newUser(), "Painter", "ACTIVE", true, adminId, now(),
            null));
    }

    private Long newUser() {
        return customerService.register(new CustomerRegistration(
            "f16-test-" + UUID.randomUUID() + "@example.invalid", "long-password", "Member")).getUserId();
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
            .orElseGet(() -> staffRepository.saveAndFlush(new StaffAccount("f16-test-" + UUID.randomUUID(),
                "unused".getBytes(StandardCharsets.UTF_8), "ADMIN", true, now())));
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(ZoneOffset.UTC).withNano(0);
    }
}
