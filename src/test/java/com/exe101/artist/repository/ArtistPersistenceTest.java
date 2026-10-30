package com.exe101.artist.repository;

import com.exe101.artist.model.ArtistGrant;
import com.exe101.artist.service.ArtistService;
import com.exe101.audit.repository.AuditRepository;
import com.exe101.common.error.AppErrorMessage;
import com.exe101.common.error.AppException;
import com.exe101.customer.model.CustomerRegistration;
import com.exe101.customer.repository.CustomerRepository;
import com.exe101.customer.service.CustomerService;
import com.exe101.entity.StaffAccount;
import com.exe101.staff.repository.StaffRepository;
import com.exe101.staff.security.StaffTokenIssuer;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "DB_URL", matches = ".+")
class ArtistPersistenceTest {
    @Autowired
    private ArtistService service;
    @Autowired
    private ArtistRepository repository;
    @Autowired
    private CustomerService customerService;
    @Autowired
    private CustomerRepository customerRepository;
    @Autowired
    private StaffRepository staffRepository;
    @Autowired
    private AuditRepository auditRepository;
    @Autowired
    private StaffTokenIssuer staffIssuer;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private MockMvc mvc;
    @Autowired
    private JsonMapper jsonMapper;

    @Test
    void grant_artist_profile_keeps_customer_access_ok() {
        // given
        var email = uniqueEmail();
        var transaction = new TransactionTemplate(transactionManager);

        // when / then
        transaction.executeWithoutResult(status -> {
            exerciseGrantFlow(email, activeAdmin().getStaffId());
            status.setRollbackOnly();
        });
        assertThat(customerRepository.findByNormalizedEmail(email)).isEmpty();
    }

    private void exerciseGrantFlow(String email, Long adminId) {
        try {
            var registered = mvc.perform(post("/customers/register").contentType(MediaType.APPLICATION_JSON).content("""
                    {"email":"%s","password":"long-password","displayName":"Future Artist"}
                    """.formatted(email)))
                .andExpect(status().isCreated()).andReturn();
            var userId = jsonMapper.readTree(registered.getResponse().getContentAsString()).get("userId").asLong();
            var login = mvc.perform(post("/customers/login").contentType(MediaType.APPLICATION_JSON).content("""
                    {"email":"%s","password":"long-password"}
                    """.formatted(email)))
                .andExpect(status().isOk()).andReturn();
            var customerToken = jsonMapper.readTree(login.getResponse().getContentAsString()).get("accessToken").asText();
            var adminToken = staffIssuer.issue(adminId).getAccessToken();
            mvc.perform(post("/artists").header("Authorization", "Bearer " + adminToken)
                    .contentType(MediaType.APPLICATION_JSON).content("""
                        {"userId":%d,"displayName":"Painter"}
                        """.formatted(userId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.userId").value(userId))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.acceptingCommissions").value(false));
            mvc.perform(get("/customers/me").header("Authorization", "Bearer " + customerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(userId));
        } catch (Exception exception) {
            throw new AssertionError("Artist grant HTTP flow failed", exception);
        }
    }

    @Test
    void grant_artist_profile_user_not_found_ko() {
        // given
        var transaction = new TransactionTemplate(transactionManager);
        var before = repository.count();

        // when / then
        transaction.executeWithoutResult(status -> {
            var adminId = activeAdmin().getStaffId();
            assertThatThrownBy(() -> service.grant(new ArtistGrant(Long.MAX_VALUE, "Painter", null), adminId))
                .isInstanceOf(AppException.class).hasMessage(AppErrorMessage.USER_NOT_FOUND.getDetail());
            assertThat(repository.count()).isEqualTo(before);
            status.setRollbackOnly();
        });
    }

    @Test
    void grant_artist_profile_unavailable_user_ko() {
        // given
        var transaction = new TransactionTemplate(transactionManager);

        // when / then
        for (var unavailable : List.of("BLOCKED", "DELETED")) {
            transaction.executeWithoutResult(status -> {
                var adminId = activeAdmin().getStaffId();
                var user = customerService.register(new CustomerRegistration(uniqueEmail(), "long-password", "Test"));
                var row = customerRepository.findById(user.getUserId()).orElseThrow();
                row.setStatus(unavailable);
                row.setDeletedAt("DELETED".equals(unavailable) ? row.getCreatedAt() : null);
                row.setEmail("DELETED".equals(unavailable) ? null : row.getEmail());
                row.setNormalizedEmail("DELETED".equals(unavailable) ? null : row.getNormalizedEmail());
                row.setPasswordHash("DELETED".equals(unavailable) ? null : row.getPasswordHash());
                customerRepository.saveAndFlush(row);
                assertThatThrownBy(() -> service.grant(new ArtistGrant(user.getUserId(), "Painter", null), adminId))
                    .isInstanceOf(AppException.class).hasMessage(AppErrorMessage.USER_NOT_ACTIVE.getDetail());
                assertThat(repository.existsByUserId(user.getUserId())).isFalse();
                status.setRollbackOnly();
            });
        }
    }

    @Test
    void grant_artist_profile_records_audit_ok() {
        // given
        var transaction = new TransactionTemplate(transactionManager);

        // when / then
        transaction.executeWithoutResult(status -> {
            var adminId = activeAdmin().getStaffId();
            var user = customerService.register(new CustomerRegistration(uniqueEmail(), "long-password", "Test"));
            var artist = service.grant(new ArtistGrant(user.getUserId(), "Painter", " Joined the team "), adminId);
            var audits = auditRepository.findAll().stream()
                .filter(audit -> "ArtistProfile".equals(audit.getTargetType()))
                .filter(audit -> audit.getTargetId() == artist.getArtistId())
                .toList();
            assertAll(
                () -> assertThat(audits).hasSize(1),
                () -> assertThat(audits.getFirst().getStaffId()).isEqualTo(adminId),
                () -> assertThat(audits.getFirst().getActionType()).isEqualTo("ARTIST_PROFILE_GRANTED"),
                () -> assertThat(audits.getFirst().getReason()).isEqualTo("Joined the team"),
                () -> assertThat(audits.getFirst().getOccurredAt().getOffset()).isEqualTo(ZoneOffset.UTC)
            );
            status.setRollbackOnly();
        });
    }

    @Test
    void grant_artist_profile_concurrent_ko() throws Exception {
        // given
        var transaction = new TransactionTemplate(transactionManager);
        var createdAdmin = staffRepository.findAll().stream().noneMatch(this::isActiveAdmin);
        var adminId = transaction.execute(status -> activeAdmin().getStaffId());
        var userId = customerService.register(new CustomerRegistration(uniqueEmail(), "long-password", "Race"))
            .getUserId();
        var start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);

        // when / then
        try {
            var first = executor.submit(grantAfter(start, userId, adminId));
            var second = executor.submit(grantAfter(start, userId, adminId));
            start.countDown();
            var results = List.of(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS));
            var profiles = repository.findAll().stream().filter(profile -> profile.getUserId() == userId).toList();
            assertAll(
                () -> assertThat(results).containsExactlyInAnyOrder("created", "conflict"),
                () -> assertThat(profiles).hasSize(1)
            );
        } finally {
            executor.shutdown();
            assertThat(executor.awaitTermination(35, TimeUnit.SECONDS)).isTrue();
            removeTestRecords(userId, createdAdmin ? adminId : null);
        }
    }

    private Callable<String> grantAfter(CountDownLatch start, Long userId, Long adminId) {
        return () -> {
            assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();

            try {
                service.grant(new ArtistGrant(userId, "Race Painter", null), adminId);

                return "created";
            } catch (AppException exception) {
                assertThat(exception.getError()).isEqualTo(AppErrorMessage.ARTIST_ALREADY_GRANTED);

                return "conflict";
            }
        };
    }

    private void removeTestRecords(Long userId, Long createdAdminId) {
        var transaction = new TransactionTemplate(transactionManager);
        transaction.executeWithoutResult(status -> {
            var profiles = repository.findAll().stream().filter(profile -> profile.getUserId() == userId).toList();

            for (var profile : profiles) {
                auditRepository.findAll().stream()
                    .filter(audit -> "ArtistProfile".equals(audit.getTargetType()))
                    .filter(audit -> audit.getTargetId() == profile.getArtistId())
                    .forEach(auditRepository::delete);
                repository.delete(profile);
            }

            customerRepository.deleteById(userId);

            if (createdAdminId != null) {
                staffRepository.deleteById(createdAdminId);
            }
        });
    }

    private StaffAccount activeAdmin() {
        return staffRepository.findAll().stream().filter(this::isActiveAdmin).findFirst().orElseGet(() -> {
            var admin = new StaffAccount("f02-test-" + UUID.randomUUID(), "unused".getBytes(StandardCharsets.UTF_8),
                "ADMIN", true, OffsetDateTime.now(ZoneOffset.UTC).withNano(0));

            return staffRepository.saveAndFlush(admin);
        });
    }

    private boolean isActiveAdmin(StaffAccount staff) {
        return "ADMIN".equals(staff.getRole()) && staff.isIsActive();
    }

    private String uniqueEmail() {
        return "f02-test-" + UUID.randomUUID() + "@example.invalid";
    }
}
