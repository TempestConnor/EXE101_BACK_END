package com.exe101.staff.service;

import com.exe101.common.error.AppErrorMessage;
import com.exe101.common.error.AppException;
import com.exe101.entity.StaffAccount;
import com.exe101.staff.mapper.StaffEntityMapper;
import com.exe101.staff.model.StaffLogin;
import com.exe101.staff.repository.StaffRepository;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mapstruct.factory.Mappers;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StaffServiceTest {
    @Mock
    private StaffRepository repository;
    @Mock
    private PasswordEncoder passwordEncoder;
    @Spy
    private StaffEntityMapper mapper = Mappers.getMapper(StaffEntityMapper.class);
    @InjectMocks
    private StaffServiceImpl service;

    @Test
    void staff_login_ok() {
        // given
        when(repository.findByLoginName("admin")).thenReturn(Optional.of(admin()));
        when(passwordEncoder.matches("long-password", "hash")).thenReturn(true);

        // when
        var result = service.authenticate(new StaffLogin(" Admin ", "long-password"));

        // then
        assertAll(
            () -> assertThat(result.getStaffId()).isEqualTo(7L),
            () -> assertThat(result.getRole()).isEqualTo("ADMIN")
        );
    }

    @Test
    void staff_login_wrong_password_ko() {
        // given
        when(repository.findByLoginName("admin")).thenReturn(Optional.of(admin()));
        when(passwordEncoder.matches("wrong-password", "hash")).thenReturn(false);

        // when / then
        assertThatThrownBy(() -> service.authenticate(new StaffLogin("admin", "wrong-password")))
            .isInstanceOf(AppException.class).hasMessage(AppErrorMessage.INVALID_CREDENTIALS.getDetail());
    }

    @Test
    void staff_login_unknown_login_ko() {
        // given
        when(repository.findByLoginName("nobody")).thenReturn(Optional.empty());

        // when / then
        assertThatThrownBy(() -> service.authenticate(new StaffLogin("nobody", "long-password")))
            .isInstanceOf(AppException.class).hasMessage(AppErrorMessage.INVALID_CREDENTIALS.getDetail());
        verify(passwordEncoder, never()).matches(any(), any());
    }

    @Test
    void staff_login_inactive_account_ko() {
        // given
        var inactive = admin();
        inactive.setIsActive(false);
        when(repository.findByLoginName("admin")).thenReturn(Optional.of(inactive));

        // when / then
        assertThatThrownBy(() -> service.authenticate(new StaffLogin("admin", "long-password")))
            .isInstanceOf(AppException.class).hasMessage(AppErrorMessage.INVALID_CREDENTIALS.getDetail());
    }

    @Test
    void admin_bootstrap_existing_admin_unchanged_ok() {
        // given
        when(repository.existsByRole("ADMIN")).thenReturn(true);

        // when
        service.bootstrapAdmin(new StaffLogin("admin", "another-long-password"));

        // then
        verify(repository, never()).saveAndFlush(any());
        verify(passwordEncoder, never()).encode(any());
    }

    @ParameterizedTest
    @MethodSource("invalidPasswords")
    void admin_bootstrap_invalid_password_ko(String password) {
        // given
        var login = new StaffLogin("admin", password);

        // when / then
        assertThatThrownBy(() -> service.bootstrapAdmin(login)).isInstanceOf(IllegalStateException.class);
        verify(repository, never()).saveAndFlush(any());
    }

    static Stream<String> invalidPasswords() {
        return Stream.of("short", "", "x".repeat(129));
    }

    private StaffAccount admin() {
        var staff = new StaffAccount();
        staff.setStaffId(7L);
        staff.setLoginName("admin");
        staff.setRole("ADMIN");
        staff.setIsActive(true);
        staff.setPasswordHash("hash".getBytes(StandardCharsets.UTF_8));

        return staff;
    }
}
