package com.exe101.province.service;

import com.exe101.audit.model.AuditEntry;
import com.exe101.audit.service.AuditService;
import com.exe101.common.error.AppErrorMessage;
import com.exe101.common.error.AppException;
import com.exe101.entity.SupportedProvince;
import com.exe101.province.mapper.ProvinceEntityMapper;
import com.exe101.province.model.NewProvince;
import com.exe101.province.model.ProvinceSupportChange;
import com.exe101.province.repository.ProvinceRepository;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mapstruct.factory.Mappers;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProvinceServiceTest {
    @Mock
    private ProvinceRepository repository;
    @Mock
    private AuditService auditService;
    @Spy
    private ProvinceEntityMapper mapper = Mappers.getMapper(ProvinceEntityMapper.class);
    @InjectMocks
    private ProvinceServiceImpl service;

    @Test
    void create_province_ok() {
        // given
        when(repository.saveAndFlush(any())).thenAnswer(invocation -> {
            SupportedProvince entity = invocation.getArgument(0);
            entity.setProvinceId(3L);
            return entity;
        });

        // when
        var result = service.create(new NewProvince("01", " Thành phố Hà Nội "));
        var captured = ArgumentCaptor.forClass(SupportedProvince.class);
        verify(repository).saveAndFlush(captured.capture());
        var entity = captured.getValue();

        // then
        assertAll(
            () -> assertThat(result.getProvinceId()).isEqualTo(3L),
            () -> assertThat(entity.getProvinceCode()).isEqualTo("01"),
            () -> assertThat(entity.getProvinceName()).isEqualTo("Thành phố Hà Nội"),
            () -> assertThat(entity.isIsSupported()).isFalse(),
            () -> assertThat(entity.getUpdatedAt().getOffset()).isEqualTo(ZoneOffset.UTC)
        );
    }

    @Test
    void create_province_duplicate_code_ko() {
        // given
        when(repository.existsByProvinceCode("01")).thenReturn(true);

        // when / then
        assertThatThrownBy(() -> service.create(new NewProvince("01", "Thành phố Hà Nội")))
            .isInstanceOf(AppException.class).hasMessage(AppErrorMessage.PROVINCE_ALREADY_EXISTS.getDetail());
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void create_province_invalid_code_ko() {
        // given
        var oneDigit = new NewProvince("1", "Thành phố Hà Nội");
        var letters = new NewProvince("HN", "Thành phố Hà Nội");

        // when / then
        assertAll(
            () -> assertThatThrownBy(() -> service.create(oneDigit))
                .isInstanceOf(AppException.class).hasMessage(AppErrorMessage.PROVINCE_INVALID.getDetail()),
            () -> assertThatThrownBy(() -> service.create(letters))
                .isInstanceOf(AppException.class).hasMessage(AppErrorMessage.PROVINCE_INVALID.getDetail())
        );
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void set_province_support_ok() {
        // given
        var before = OffsetDateTime.of(2026, 10, 1, 0, 0, 0, 0, ZoneOffset.UTC);
        var existing = new SupportedProvince("01", "Thành phố Hà Nội", false, before);
        existing.setProvinceId(3L);
        when(repository.findById(3L)).thenReturn(Optional.of(existing));
        when(repository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

        // when
        var result = service.changeSupport(3L, new ProvinceSupportChange(true, " Courier contract signed "), 7L);
        var audit = ArgumentCaptor.forClass(AuditEntry.class);
        verify(auditService).record(audit.capture());

        // then
        assertAll(
            () -> assertThat(result.isSupported()).isTrue(),
            () -> assertThat(existing.isIsSupported()).isTrue(),
            () -> assertThat(existing.getUpdatedAt()).isAfter(before),
            () -> assertThat(audit.getValue().getStaffId()).isEqualTo(7L),
            () -> assertThat(audit.getValue().getTargetId()).isEqualTo(3L),
            () -> assertThat(audit.getValue().getReason()).isEqualTo("Courier contract signed")
        );
    }

    @Test
    void set_province_support_not_found_ko() {
        // given
        when(repository.findById(3L)).thenReturn(Optional.empty());

        // when / then
        assertThatThrownBy(() -> service.changeSupport(3L, new ProvinceSupportChange(true, null), 7L))
            .isInstanceOf(AppException.class).hasMessage(AppErrorMessage.PROVINCE_NOT_FOUND.getDetail());
        verify(repository, never()).saveAndFlush(any());
        verify(auditService, never()).record(any());
    }
}
