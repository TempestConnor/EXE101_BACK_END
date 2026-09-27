package com.exe101.customer.service;

import com.exe101.common.error.AppErrorMessage;
import com.exe101.common.error.AppException;
import com.exe101.customer.mapper.CustomerEntityMapper;
import com.exe101.customer.model.CustomerLogin;
import com.exe101.customer.model.CustomerRegistration;
import com.exe101.customer.repository.CustomerRepository;
import com.exe101.entity.MarketplaceUser;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.ZoneOffset;
import java.util.Optional;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mapstruct.factory.Mappers;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CustomerServiceTest {
    @Mock
    private CustomerRepository repository;
    @Mock
    private PasswordEncoder passwordEncoder;
    @Spy
    private CustomerEntityMapper mapper = Mappers.getMapper(CustomerEntityMapper.class);
    @InjectMocks
    private CustomerServiceImpl service;

    @Test
    void register_normalizes_email_and_sets_server_fields_ok() {
        // given
        var registration = registration();
        when(passwordEncoder.encode(registration.getPassword())).thenReturn("{encoded}hash");
        when(repository.saveAndFlush(any())).thenAnswer(invocation -> {
            MarketplaceUser entity = invocation.getArgument(0);
            entity.setUserId(42L);
            return entity;
        });

        // when
        var result = service.register(registration);
        var captured = ArgumentCaptor.forClass(MarketplaceUser.class);
        verify(repository).saveAndFlush(captured.capture());
        var entity = captured.getValue();

        // then
        assertAll(
            () -> assertThat(result.getUserId()).isEqualTo(42L),
            () -> assertThat(entity.getNormalizedEmail()).isEqualTo("buyer@example.com"),
            () -> assertThat(entity.getEmail()).isEqualTo("buyer@example.com"),
            () -> assertThat(entity.getStatus()).isEqualTo("ACTIVE"),
            () -> assertThat(entity.getDisplayName()).isEqualTo("Buyer"),
            () -> assertThat(entity.getPasswordHash()).isEqualTo("{encoded}hash".getBytes(StandardCharsets.UTF_8)),
            () -> assertThat(entity.getCreatedAt().getOffset()).isEqualTo(ZoneOffset.UTC),
            () -> assertThat(entity.getDeletedAt()).isNull(),
            () -> assertThat(entity.getRowVer()).isNull()
        );
    }

    @Test
    void register_existing_email_ko() {
        // given
        when(repository.existsByNormalizedEmail("buyer@example.com")).thenReturn(true);

        // when / then
        assertThatThrownBy(() -> service.register(registration()))
            .isInstanceOf(AppException.class).hasMessage(AppErrorMessage.EMAIL_ALREADY_REGISTERED.getDetail());
        verify(repository, never()).saveAndFlush(any());
        verify(passwordEncoder, never()).encode(any());
    }

    @Test
    void register_duplicate_race_maps_only_email_index_ko() {
        // given
        var sql = new SQLException("duplicate", "23000", 2601);
        var constraint = new ConstraintViolationException("duplicate", sql, "UX_User_Email");
        when(passwordEncoder.encode(any())).thenReturn("hash");
        when(repository.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("duplicate", constraint));

        // when / then
        assertThatThrownBy(() -> service.register(registration()))
            .isInstanceOf(AppException.class).hasMessage(AppErrorMessage.EMAIL_ALREADY_REGISTERED.getDetail());
    }

    @Test
    void register_unrelated_database_failure_is_not_email_conflict_ko() {
        // given
        var failure = new DataIntegrityViolationException("unrelated failure");
        when(passwordEncoder.encode(any())).thenReturn("hash");
        when(repository.saveAndFlush(any())).thenThrow(failure);

        // when / then
        assertThatThrownBy(() -> service.register(registration())).isSameAs(failure);
    }

    @Test
    void authenticate_current_customer_ok() {
        // given
        when(repository.findByNormalizedEmail("buyer@example.com")).thenReturn(Optional.of(active()));
        when(passwordEncoder.matches("long-password", "hash")).thenReturn(true);

        // when
        var result = service.authenticate(login());

        // then
        assertThat(result.getUserId()).isEqualTo(42L);
    }

    @Test
    void authenticate_unknown_customer_ko() {
        // given
        when(repository.findByNormalizedEmail("buyer@example.com")).thenReturn(Optional.empty());

        // when / then
        assertThatThrownBy(() -> service.authenticate(login())).isInstanceOf(AppException.class);
    }

    @Test
    void authenticate_wrong_password_ko() {
        // given
        when(repository.findByNormalizedEmail("buyer@example.com")).thenReturn(Optional.of(active()));

        // when / then
        assertThatThrownBy(() -> service.authenticate(login())).isInstanceOf(AppException.class);
    }

    @Test
    void authenticate_blocked_customer_ko() {
        // given
        var customer = active();
        customer.setStatus("BLOCKED");
        when(repository.findByNormalizedEmail("buyer@example.com")).thenReturn(Optional.of(customer));

        // when / then
        assertThatThrownBy(() -> service.authenticate(login())).isInstanceOf(AppException.class);
    }

    @Test
    void authenticate_missing_password_hash_ko() {
        // given
        var customer = active();
        customer.setPasswordHash(null);
        when(repository.findByNormalizedEmail("buyer@example.com")).thenReturn(Optional.of(customer));

        // when / then
        assertThatThrownBy(() -> service.authenticate(login())).isInstanceOf(AppException.class);
    }

    @Test
    void require_active_deleted_customer_ko() {
        // given
        var customer = active();
        customer.setStatus("DELETED");
        when(repository.findById(42L)).thenReturn(Optional.of(customer));

        // when / then
        assertThatThrownBy(() -> service.requireActive(42L)).isInstanceOf(AppException.class);
    }

    @Test
    void require_active_missing_customer_ko() {
        // given
        when(repository.findById(42L)).thenReturn(Optional.empty());

        // when / then
        assertThatThrownBy(() -> service.requireActive(42L)).isInstanceOf(AppException.class);
    }

    private CustomerRegistration registration() {
        return new CustomerRegistration("Buyer@Example.COM", "long-password", " Buyer ");
    }

    private CustomerLogin login() {
        return new CustomerLogin("Buyer@Example.COM", "long-password");
    }

    private MarketplaceUser active() {
        var customer = new MarketplaceUser();
        customer.setUserId(42L);
        customer.setStatus("ACTIVE");
        customer.setPasswordHash("hash".getBytes(StandardCharsets.UTF_8));

        return customer;
    }
}
