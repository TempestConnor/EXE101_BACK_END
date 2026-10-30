package com.exe101.artist.repository;

import com.exe101.artist.model.ArtistGrant;
import com.exe101.artist.service.ArtistService;
import com.exe101.audit.service.AuditService;
import com.exe101.customer.model.CustomerRegistration;
import com.exe101.customer.repository.CustomerRepository;
import com.exe101.customer.service.CustomerService;
import com.exe101.entity.StaffAccount;
import com.exe101.staff.repository.StaffRepository;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

@SpringBootTest
@EnabledIfEnvironmentVariable(named = "DB_URL", matches = ".+")
class ArtistAuditRollbackTest {
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
    @MockitoBean
    private AuditService auditService;

    @Test
    void grant_artist_profile_audit_failure_rolls_back_ko() {
        // given
        var existingAdmin = staffRepository.findAll().stream()
            .filter(staff -> "ADMIN".equals(staff.getRole()) && staff.isIsActive()).findFirst();
        var admin = existingAdmin.orElseGet(() -> staffRepository.saveAndFlush(new StaffAccount(
            "f02-test-" + UUID.randomUUID(), "unused".getBytes(StandardCharsets.UTF_8), "ADMIN", true,
            OffsetDateTime.now(ZoneOffset.UTC).withNano(0))));
        var userId = customerService.register(new CustomerRegistration(
            "f02-test-" + UUID.randomUUID() + "@example.invalid", "long-password", "Rollback")).getUserId();
        doThrow(new DataAccessResourceFailureException("audit unavailable")).when(auditService).record(any());

        // when / then
        try {
            assertThatThrownBy(() -> service.grant(new ArtistGrant(userId, "Painter", null), admin.getStaffId()))
                .isInstanceOf(DataAccessResourceFailureException.class);
            assertThat(repository.existsByUserId(userId)).isFalse();
        } finally {
            customerRepository.deleteById(userId);

            if (existingAdmin.isEmpty()) {
                staffRepository.deleteById(admin.getStaffId());
            }
        }
    }
}
