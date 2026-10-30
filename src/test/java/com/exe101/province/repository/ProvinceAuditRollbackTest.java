package com.exe101.province.repository;

import com.exe101.audit.service.AuditService;
import com.exe101.entity.StaffAccount;
import com.exe101.entity.SupportedProvince;
import com.exe101.province.model.ProvinceSupportChange;
import com.exe101.province.service.ProvinceService;
import com.exe101.staff.repository.StaffRepository;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
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
class ProvinceAuditRollbackTest {
    @Autowired
    private ProvinceService service;
    @Autowired
    private ProvinceRepository repository;
    @Autowired
    private StaffRepository staffRepository;
    @MockitoBean
    private AuditService auditService;

    @Test
    void province_support_audit_rollback_ko() {
        // given
        var existingAdmin = staffRepository.findAll().stream()
            .filter(staff -> "ADMIN".equals(staff.getRole()) && staff.isIsActive()).findFirst();
        var admin = existingAdmin.orElseGet(() -> staffRepository.saveAndFlush(new StaffAccount(
            "f04-test-" + UUID.randomUUID(), "unused".getBytes(StandardCharsets.UTF_8), "ADMIN", true,
            OffsetDateTime.now(ZoneOffset.UTC).withNano(0))));
        // A test-only code outside the official two-digit range, so it never collides with real rows.
        var code = "T" + ThreadLocalRandom.current().nextInt(10_000_000, 100_000_000);
        var province = repository.saveAndFlush(new SupportedProvince(code, "Test Province", false,
            OffsetDateTime.now(ZoneOffset.UTC).withNano(0)));
        doThrow(new DataAccessResourceFailureException("audit unavailable")).when(auditService).record(any());

        // when / then
        try {
            assertThatThrownBy(() -> service.changeSupport(province.getProvinceId(),
                new ProvinceSupportChange(true, null), admin.getStaffId()))
                .isInstanceOf(DataAccessResourceFailureException.class);
            assertThat(repository.findById(province.getProvinceId()).orElseThrow().isIsSupported()).isFalse();
        } finally {
            repository.deleteById(province.getProvinceId());

            if (existingAdmin.isEmpty()) {
                staffRepository.deleteById(admin.getStaffId());
            }
        }
    }
}
