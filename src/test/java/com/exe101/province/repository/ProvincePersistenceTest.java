package com.exe101.province.repository;

import com.exe101.audit.repository.AuditRepository;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

@SpringBootTest
@EnabledIfEnvironmentVariable(named = "DB_URL", matches = ".+")
class ProvincePersistenceTest {
    @Autowired
    private ProvinceService service;
    @Autowired
    private ProvinceRepository repository;
    @Autowired
    private AuditRepository auditRepository;
    @Autowired
    private StaffRepository staffRepository;
    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    void list_supported_provinces_ok() {
        // given
        var transaction = new TransactionTemplate(transactionManager);

        // when / then
        transaction.executeWithoutResult(status -> {
            var supported = province(true);
            var unsupported = province(false);
            var codes = service.listSupported().stream().map(province -> province.getProvinceCode()).toList();
            assertAll(
                () -> assertThat(codes).contains(supported.getProvinceCode()),
                () -> assertThat(codes).doesNotContain(unsupported.getProvinceCode())
            );
            status.setRollbackOnly();
        });
    }

    @Test
    void set_province_support_writes_audit_ok() {
        // given
        var transaction = new TransactionTemplate(transactionManager);

        // when / then
        transaction.executeWithoutResult(status -> {
            var adminId = activeAdmin().getStaffId();
            var province = province(false);
            service.changeSupport(province.getProvinceId(), new ProvinceSupportChange(true, " New courier "), adminId);
            var audits = auditRepository.findAll().stream()
                .filter(audit -> "SupportedProvince".equals(audit.getTargetType()))
                .filter(audit -> audit.getTargetId() == province.getProvinceId())
                .toList();
            assertAll(
                () -> assertThat(audits).hasSize(1),
                () -> assertThat(audits.getFirst().getStaffId()).isEqualTo(adminId),
                () -> assertThat(audits.getFirst().getActionType()).isEqualTo("PROVINCE_SUPPORT_CHANGED"),
                () -> assertThat(audits.getFirst().getReason()).isEqualTo("New courier"),
                () -> assertThat(audits.getFirst().getDetails()).contains(province.getProvinceCode()),
                () -> assertThat(repository.findById(province.getProvinceId()).orElseThrow().isIsSupported()).isTrue()
            );
            status.setRollbackOnly();
        });
    }

    private SupportedProvince province(boolean supported) {
        // A test-only code outside the official two-digit range, so it never collides with real rows.
        var code = "T" + ThreadLocalRandom.current().nextInt(10_000_000, 100_000_000);

        return repository.saveAndFlush(new SupportedProvince(code, "Test Province", supported,
            OffsetDateTime.now(ZoneOffset.UTC).withNano(0)));
    }

    private StaffAccount activeAdmin() {
        return staffRepository.findAll().stream()
            .filter(staff -> "ADMIN".equals(staff.getRole()) && staff.isIsActive())
            .findFirst()
            .orElseGet(() -> staffRepository.saveAndFlush(new StaffAccount("f04-test-" + UUID.randomUUID(),
                "unused".getBytes(StandardCharsets.UTF_8), "ADMIN", true,
                OffsetDateTime.now(ZoneOffset.UTC).withNano(0))));
    }
}
