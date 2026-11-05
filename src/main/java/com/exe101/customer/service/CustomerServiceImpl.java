package com.exe101.customer.service;

import com.exe101.common.error.AppErrorMessage;
import com.exe101.common.error.AppException;
import com.exe101.customer.mapper.CustomerEntityMapper;
import com.exe101.customer.model.Customer;
import com.exe101.customer.model.CustomerLogin;
import com.exe101.customer.model.CustomerRegistration;
import com.exe101.customer.repository.CustomerRepository;
import com.exe101.entity.MarketplaceUser;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
@RequiredArgsConstructor
public class CustomerServiceImpl implements CustomerService {
    private static String activeStatus = "ACTIVE";
    private static String emailIndex = "UX_User_Email";
    private final CustomerRepository repository;
    private final CustomerEntityMapper mapper;
    private final PasswordEncoder passwordEncoder;

    @Override
    public Customer register(CustomerRegistration registration) {
        String normalizedEmail = normalizeEmail(registration.getEmail());

        if (repository.existsByNormalizedEmail(normalizedEmail)) {
            throw new AppException(AppErrorMessage.EMAIL_ALREADY_REGISTERED);
        }

        MarketplaceUser entity = mapper.toEntity(registration);
        entity.setEmail(normalizedEmail);
        entity.setNormalizedEmail(normalizedEmail);
        entity.setDisplayName(registration.getDisplayName().strip());
        entity.setPasswordHash(passwordEncoder.encode(registration.getPassword()).getBytes(StandardCharsets.UTF_8));
        entity.setStatus(activeStatus);
        entity.setCreatedAt(OffsetDateTime.now(ZoneOffset.UTC).withNano(0));

        try {
            MarketplaceUser saved = repository.saveAndFlush(entity);

            return mapper.toModel(saved);
        } catch (DataIntegrityViolationException exception) {
            if (isEmailConflict(exception)) {
                throw new AppException(AppErrorMessage.EMAIL_ALREADY_REGISTERED);
            }

            throw exception;
        }
    }

    @Override
    public Customer authenticate(CustomerLogin login) {
        MarketplaceUser entity = repository.findByNormalizedEmail(normalizeEmail(login.getEmail()))
            .orElseThrow(() -> new AppException(AppErrorMessage.INVALID_CREDENTIALS));
        requireActiveAccount(entity);
        byte[] hash = entity.getPasswordHash();

        if (hash == null || !passwordEncoder.matches(login.getPassword(), new String(hash, StandardCharsets.UTF_8))) {
            throw new AppException(AppErrorMessage.INVALID_CREDENTIALS);
        }

        return mapper.toModel(entity);
    }

    @Override
    public Customer requireActive(Long userId) {
        MarketplaceUser entity = repository.findById(userId)
            .orElseThrow(() -> new AppException(AppErrorMessage.INVALID_CREDENTIALS));
        requireActiveAccount(entity);

        return mapper.toModel(entity);
    }

    @Override
    public Customer getActive(Long userId) {
        MarketplaceUser entity = repository.findById(userId)
            .orElseThrow(() -> new AppException(AppErrorMessage.USER_NOT_FOUND));

        if (!isActive(entity)) {
            throw new AppException(AppErrorMessage.USER_NOT_ACTIVE);
        }

        return mapper.toModel(entity);
    }

    // For the Admin: one account by its exact email, normalized as at registration and login; whatever its status,
    // so a blocked account is found too. No partial match, so accounts cannot be browsed.
    @Override
    @Transactional(readOnly = true)
    public Customer findByEmail(String email) {
        MarketplaceUser entity = repository.findByNormalizedEmail(normalizeEmail(email))
            .orElseThrow(() -> new AppException(AppErrorMessage.USER_NOT_FOUND));

        return mapper.toModel(entity);
    }

    private void requireActiveAccount(MarketplaceUser entity) {
        if (!isActive(entity)) {
            throw new AppException(AppErrorMessage.INVALID_CREDENTIALS);
        }
    }

    private boolean isActive(MarketplaceUser entity) {
        return activeStatus.equals(entity.getStatus()) && entity.getDeletedAt() == null;
    }

    private String normalizeEmail(String email) {
        return email.strip().toLowerCase(Locale.ROOT);
    }

    private boolean isEmailConflict(Throwable exception) {
        Throwable cause = exception;

        while (cause != null) {
            if (cause instanceof ConstraintViolationException violation) {
                return emailIndex.equalsIgnoreCase(violation.getConstraintName());
            }

            cause = cause.getCause();
        }

        return false;
    }
}
