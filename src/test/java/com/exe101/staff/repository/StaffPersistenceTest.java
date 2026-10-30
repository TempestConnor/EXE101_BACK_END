package com.exe101.staff.repository;

import com.exe101.staff.model.StaffLogin;
import com.exe101.staff.service.StaffService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "DB_URL", matches = ".+")
class StaffPersistenceTest {
    @Autowired
    private StaffService service;
    @Autowired
    private StaffRepository repository;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private MockMvc mvc;

    @Test
    void admin_bootstrap_creates_admin_ok() {
        // given
        assumeThat(repository.existsByRole("ADMIN")).as("database already has an Admin").isFalse();
        var transaction = new TransactionTemplate(transactionManager);

        // when / then
        transaction.executeWithoutResult(status -> {
            service.bootstrapAdmin(new StaffLogin(" F02-Test-Admin ", "long-admin-password"));
            var admins = repository.findAll().stream().filter(staff -> "ADMIN".equals(staff.getRole())).toList();
            assertAll(
                () -> assertThat(admins).hasSize(1),
                () -> assertThat(admins.getFirst().getLoginName()).isEqualTo("f02-test-admin"),
                () -> assertThat(admins.getFirst().isIsActive()).isTrue()
            );
            login("F02-TEST-ADMIN", "long-admin-password");
            status.setRollbackOnly();
        });
        assertThat(repository.existsByRole("ADMIN")).isFalse();
    }

    private void login(String loginName, String password) {
        try {
            mvc.perform(post("/staff/login").contentType(MediaType.APPLICATION_JSON).content("""
                    {"loginName":"%s","password":"%s"}
                    """.formatted(loginName, password)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tokenType").value("Bearer"));
        } catch (Exception exception) {
            throw new AssertionError("Staff login failed", exception);
        }
    }
}
