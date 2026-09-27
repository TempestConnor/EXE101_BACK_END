package com.exe101.customer.repository;

import com.exe101.common.error.AppException;
import com.exe101.customer.model.CustomerLogin;
import com.exe101.customer.model.CustomerRegistration;
import com.exe101.customer.service.CustomerService;
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
import tools.jackson.databind.json.JsonMapper;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

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
class CustomerPersistenceTest {
    @Autowired
    private CustomerService service;
    @Autowired
    private CustomerRepository repository;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private MockMvc mvc;
    @Autowired
    private JsonMapper jsonMapper;

    @Test
    void register_login_and_access_with_real_persistence_ok() {
        // given
        var email = uniqueEmail();
        var transaction = new TransactionTemplate(transactionManager);

        // when / then
        transaction.executeWithoutResult(transactionStatus -> {
            exerciseHttpFlow(email);
            transactionStatus.setRollbackOnly();
        });
        assertThat(repository.findByNormalizedEmail(email)).isEmpty();
    }

    private void exerciseHttpFlow(String email) {
        try {
            mvc.perform(post("/customers/register").contentType(MediaType.APPLICATION_JSON).content("""
                    {"email":"%s","password":"long-password","displayName":"HTTP Test"}
                    """.formatted(email)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.email").value(email));
            var login = mvc.perform(post("/customers/login").contentType(MediaType.APPLICATION_JSON).content("""
                    {"email":"%s","password":"long-password"}
                    """.formatted(email)))
                .andExpect(status().isOk()).andReturn();
            var token = jsonMapper.readTree(login.getResponse().getContentAsString()).get("accessToken").asText();
            mvc.perform(get("/customers/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.email").value(email));
            var row = repository.findByNormalizedEmail(email).orElseThrow();
            row.setStatus("BLOCKED");
            repository.saveAndFlush(row);
            mvc.perform(get("/customers/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
        } catch (Exception exception) {
            throw new AssertionError("Customer HTTP flow failed", exception);
        }
    }

    @Test
    void registration_persists_hash_identity_and_rowversion_ok() {
        // given
        var email = uniqueEmail();
        var transaction = new TransactionTemplate(transactionManager);

        // when / then
        transaction.executeWithoutResult(status -> {
            var created = service.register(new CustomerRegistration(email, "long-password", "Test Buyer"));
            var row = repository.findById(created.getUserId()).orElseThrow();
            var authenticated = service.authenticate(new CustomerLogin(email.toUpperCase(), "long-password"));
            assertAll(
                () -> assertThat(row.getRowVer()).hasSize(8),
                () -> assertThat(row.getStatus()).isEqualTo("ACTIVE"),
                () -> assertThat(authenticated.getUserId()).isEqualTo(created.getUserId()),
                () -> assertThat(row.getPasswordHash()).isNotEmpty()
            );
            status.setRollbackOnly();
        });
        assertThat(repository.findByNormalizedEmail(email)).isEmpty();
    }

    @Test
    void account_status_is_checked_again_after_blocking_ok() {
        // given
        var email = uniqueEmail();
        var transaction = new TransactionTemplate(transactionManager);

        // when / then
        transaction.executeWithoutResult(status -> {
            var created = service.register(new CustomerRegistration(email, "long-password", "Test Buyer"));
            var row = repository.findById(created.getUserId()).orElseThrow();
            row.setStatus("BLOCKED");
            repository.saveAndFlush(row);
            assertThatThrownBy(() -> service.requireActive(created.getUserId())).isInstanceOf(AppException.class);
            assertThatThrownBy(() -> service.authenticate(new CustomerLogin(email, "long-password")))
                .isInstanceOf(AppException.class);
            status.setRollbackOnly();
        });
    }

    @Test
    void registration_after_existing_deleted_identity_keeps_old_identity_ok() {
        // given
        var email = uniqueEmail();
        var transaction = new TransactionTemplate(transactionManager);

        // when / then
        transaction.executeWithoutResult(status -> {
            var previous = service.register(new CustomerRegistration(email, "long-password", "Old Test Buyer"));
            var oldRow = repository.findById(previous.getUserId()).orElseThrow();
            oldRow.setStatus("DELETED");
            oldRow.setDeletedAt(oldRow.getCreatedAt());
            oldRow.setEmail(null);
            oldRow.setNormalizedEmail(null);
            oldRow.setPasswordHash(null);
            repository.saveAndFlush(oldRow);
            var current = service.register(new CustomerRegistration(email, "long-password", "New Test Buyer"));
            assertAll(
                () -> assertThat(current.getUserId()).isNotEqualTo(previous.getUserId()),
                () -> assertThat(repository.findById(previous.getUserId())).isPresent()
            );
            status.setRollbackOnly();
        });
    }

    @Test
    void concurrent_registrations_commit_one_identity_and_return_conflict_ok() throws Exception {
        // given
        var email = uniqueEmail();
        var start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);

        // when / then
        try {
            var first = executor.submit(registerAfter(start, email));
            var second = executor.submit(registerAfter(start, email.toUpperCase()));
            start.countDown();
            var results = List.of(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS));
            assertAll(
                () -> assertThat(results).containsExactlyInAnyOrder("created", "conflict"),
                () -> assertThat(repository.findByNormalizedEmail(email)).isPresent()
            );
        } finally {
            executor.shutdown();
            assertThat(executor.awaitTermination(35, TimeUnit.SECONDS)).isTrue();
            removeTestAccount(email);
        }
    }

    private Callable<String> registerAfter(CountDownLatch start, String email) {
        return () -> {
            assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();

            try {
                service.register(new CustomerRegistration(email, "long-password", "Concurrency Test"));

                return "created";
            } catch (AppException exception) {
                assertThat(exception.getError().name()).isEqualTo("EMAIL_ALREADY_REGISTERED");

                return "conflict";
            }
        };
    }

    private void removeTestAccount(String email) {
        var transaction = new TransactionTemplate(transactionManager);
        transaction.executeWithoutResult(status ->
            repository.findByNormalizedEmail(email).ifPresent(repository::delete));
    }

    private String uniqueEmail() {
        return "f01-test-" + UUID.randomUUID() + "@example.invalid";
    }
}
