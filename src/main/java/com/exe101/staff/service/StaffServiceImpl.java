package com.exe101.staff.service;

import com.exe101.common.error.AppErrorMessage;
import com.exe101.common.error.AppException;
import com.exe101.entity.StaffAccount;
import com.exe101.staff.mapper.StaffEntityMapper;
import com.exe101.staff.model.Staff;
import com.exe101.staff.model.StaffLogin;
import com.exe101.staff.repository.StaffRepository;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@Transactional
@RequiredArgsConstructor
public class StaffServiceImpl implements StaffService {
    private static String adminRole = "ADMIN";
    private final StaffRepository repository;
    private final StaffEntityMapper mapper;
    private final PasswordEncoder passwordEncoder;

    @Override
    public Staff authenticate(StaffLogin login) {
        StaffAccount entity = repository.findByLoginName(normalizeLoginName(login.getLoginName()))
            .orElseThrow(() -> new AppException(AppErrorMessage.INVALID_CREDENTIALS));
        requireActiveAccount(entity);
        String hash = new String(entity.getPasswordHash(), StandardCharsets.UTF_8);

        if (!passwordEncoder.matches(login.getPassword(), hash)) {
            throw new AppException(AppErrorMessage.INVALID_CREDENTIALS);
        }

        return mapper.toModel(entity);
    }

    @Override
    public Staff requireActive(Long staffId) {
        StaffAccount entity = repository.findById(staffId)
            .orElseThrow(() -> new AppException(AppErrorMessage.INVALID_CREDENTIALS));
        requireActiveAccount(entity);

        return mapper.toModel(entity);
    }

    @Override
    public void bootstrapAdmin(StaffLogin login) {
        String loginName = login.getLoginName() == null ? "" : normalizeLoginName(login.getLoginName());
        String password = login.getPassword() == null ? "" : login.getPassword();

        // Misconfiguration fails startup instead of creating an Admin that can never log in.
        if (loginName.isEmpty() || loginName.length() > 160) {
            throw new IllegalStateException("ADMIN_LOGIN_NAME must contain 1-160 characters.");
        }

        if (password.length() < 12 || password.length() > 128) {
            throw new IllegalStateException("ADMIN_PASSWORD must contain 12-128 characters.");
        }

        if (repository.existsByRole(adminRole)) {
            log.info("[STAFF] - ACTION: bootstrapAdmin: skipped: {}", "admin exists");

            return;
        }

        StaffAccount entity = mapper.toEntity(login);
        entity.setLoginName(loginName);
        entity.setPasswordHash(passwordEncoder.encode(password).getBytes(StandardCharsets.UTF_8));
        entity.setRole(adminRole);
        entity.setIsActive(true);
        entity.setCreatedAt(OffsetDateTime.now(ZoneOffset.UTC).withNano(0));
        StaffAccount saved = repository.saveAndFlush(entity);
        log.info("[STAFF] - ACTION: bootstrapAdmin: staffId: {}", saved.getStaffId());
    }

    private void requireActiveAccount(StaffAccount entity) {
        if (!entity.isIsActive()) {
            throw new AppException(AppErrorMessage.INVALID_CREDENTIALS);
        }
    }

    private String normalizeLoginName(String loginName) {
        return loginName.strip().toLowerCase(Locale.ROOT);
    }
}
