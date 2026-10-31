package com.exe101.province.service;

import com.exe101.audit.model.AuditEntry;
import com.exe101.audit.service.AuditService;
import com.exe101.common.error.AppErrorMessage;
import com.exe101.common.error.AppException;
import com.exe101.common.persistence.DatabaseErrors;
import com.exe101.entity.SupportedProvince;
import com.exe101.province.mapper.ProvinceEntityMapper;
import com.exe101.province.model.NewProvince;
import com.exe101.province.model.Province;
import com.exe101.province.model.ProvinceSupportChange;
import com.exe101.province.repository.ProvinceRepository;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@Transactional
@RequiredArgsConstructor
public class ProvinceServiceImpl implements ProvinceService {
    private static String supportAction = "PROVINCE_SUPPORT_CHANGED";
    private static String provinceTarget = "SupportedProvince";
    // Official two-digit code of a province-level unit.
    private static Pattern provinceCodeFormat = Pattern.compile("\\d{2}");
    private static int maxNameLength = 160;
    private final ProvinceRepository repository;
    private final ProvinceEntityMapper mapper;
    private final AuditService auditService;

    @Override
    public Province create(NewProvince province) {
        String code = province.getProvinceCode() == null ? null : province.getProvinceCode().strip();
        String name = province.getProvinceName() == null ? null : province.getProvinceName().strip();
        boolean validCode = code != null && provinceCodeFormat.matcher(code).matches();
        boolean validName = name != null && !name.isEmpty() && name.length() <= maxNameLength;

        if (!validCode || !validName) {
            throw new AppException(AppErrorMessage.PROVINCE_INVALID);
        }

        if (repository.existsByProvinceCode(code)) {
            throw new AppException(AppErrorMessage.PROVINCE_ALREADY_EXISTS);
        }

        SupportedProvince entity = mapper.toEntity(province);
        entity.setProvinceCode(code);
        entity.setProvinceName(name);
        entity.setIsSupported(false);
        entity.setUpdatedAt(now());
        SupportedProvince saved;

        try {
            saved = repository.saveAndFlush(entity);
        } catch (DataIntegrityViolationException exception) {
            if (DatabaseErrors.isDuplicateKey(exception)) {
                throw new AppException(AppErrorMessage.PROVINCE_ALREADY_EXISTS);
            }

            throw exception;
        }

        log.info("[PROVINCE] - ACTION: create: provinceId: {}", saved.getProvinceId());

        return mapper.toModel(saved);
    }

    @Override
    public Province changeSupport(Long provinceId, ProvinceSupportChange change, Long staffId) {
        SupportedProvince entity = repository.findById(provinceId)
            .orElseThrow(() -> new AppException(AppErrorMessage.PROVINCE_NOT_FOUND));
        entity.setIsSupported(change.isSupported());
        entity.setUpdatedAt(now());
        SupportedProvince saved = repository.saveAndFlush(entity);

        AuditEntry audit = AuditEntry.builder()
            .withStaffId(staffId)
            .withActionType(supportAction)
            .withTargetType(provinceTarget)
            .withTargetId(saved.getProvinceId())
            .withReason(blankToNull(change.getReason()))
            .withDetails("provinceCode=" + saved.getProvinceCode() + ";supported=" + saved.isIsSupported())
            .build();
        auditService.record(audit);
        log.info("[PROVINCE] - ACTION: changeSupport: provinceId: {}", saved.getProvinceId());

        return mapper.toModel(saved);
    }

    @Override
    public List<Province> listAll() {
        return mapper.toModel(repository.findAllByOrderByProvinceCodeAsc());
    }

    @Override
    public List<Province> listSupported() {
        return mapper.toModel(repository.findByIsSupportedTrueOrderByProvinceCodeAsc());
    }

    @Override
    public Province requireSupported(String provinceCode) {
        SupportedProvince entity = repository.findByProvinceCode(provinceCode)
            .orElseThrow(() -> new AppException(AppErrorMessage.PROVINCE_UNKNOWN));

        if (!entity.isIsSupported()) {
            throw new AppException(AppErrorMessage.PROVINCE_NOT_SUPPORTED);
        }

        return mapper.toModel(entity);
    }

    // Unlike requireSupported, does not throw, so a read-only caller's transaction is not marked for rollback.
    @Override
    public boolean isSupported(String provinceCode) {
        return repository.findByProvinceCode(provinceCode)
            .map(SupportedProvince::isIsSupported)
            .orElse(false);
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(ZoneOffset.UTC).withNano(0);
    }

    private String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }

        return value.strip();
    }
}
