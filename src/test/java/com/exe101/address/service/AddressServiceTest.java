package com.exe101.address.service;

import com.exe101.address.mapper.AddressEntityMapper;
import com.exe101.address.model.AddressDetails;
import com.exe101.address.repository.AddressRepository;
import com.exe101.common.error.AppErrorMessage;
import com.exe101.common.error.AppException;
import com.exe101.entity.CustomerAddress;
import com.exe101.province.model.Province;
import com.exe101.province.service.ProvinceService;
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
class AddressServiceTest {
    @Mock
    private AddressRepository repository;
    @Mock
    private ProvinceService provinceService;
    @Spy
    private AddressEntityMapper mapper = Mappers.getMapper(AddressEntityMapper.class);
    @InjectMocks
    private AddressServiceImpl service;

    @Test
    void create_address_ok() {
        // given
        when(provinceService.requireSupported("01")).thenReturn(supported("01"));
        when(repository.saveAndFlush(any())).thenAnswer(invocation -> {
            CustomerAddress entity = invocation.getArgument(0);
            entity.setAddressId(5L);
            return entity;
        });

        // when
        var result = service.create(42L, details(false));
        var captured = ArgumentCaptor.forClass(CustomerAddress.class);
        verify(repository).saveAndFlush(captured.capture());
        var entity = captured.getValue();

        // then
        assertAll(
            () -> assertThat(result.getAddressId()).isEqualTo(5L),
            () -> assertThat(entity.getUserId()).isEqualTo(42L),
            () -> assertThat(entity.getRecipientName()).isEqualTo("Nguyễn Văn A"),
            () -> assertThat(entity.getPhone()).isEqualTo("0912345678"),
            () -> assertThat(entity.getProvinceCode()).isEqualTo("01"),
            () -> assertThat(entity.getCountryCode()).isEqualTo("VN"),
            () -> assertThat(entity.isIsDefault()).isFalse(),
            () -> assertThat(entity.getDeletedAt()).isNull(),
            () -> assertThat(entity.getCreatedAt().getOffset()).isEqualTo(ZoneOffset.UTC)
        );
    }

    @Test
    void create_address_missing_required_field_ko() {
        // given
        var details = details(false);
        details.setWard("  ");

        // when / then
        assertThatThrownBy(() -> service.create(42L, details))
            .isInstanceOf(AppException.class).hasMessage(AppErrorMessage.ADDRESS_INVALID.getDetail());
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void create_address_non_vn_country_ko() {
        // given
        var details = details(false);
        details.setCountryCode("US");

        // when / then
        assertThatThrownBy(() -> service.create(42L, details))
            .isInstanceOf(AppException.class).hasMessage(AppErrorMessage.ADDRESS_INVALID.getDetail());
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void create_address_invalid_phone_ko() {
        // given
        var international = details(false);
        international.setPhone("+84912345678");
        var tooShort = details(false);
        tooShort.setPhone("091234567");

        // when / then
        assertAll(
            () -> assertThatThrownBy(() -> service.create(42L, international))
                .isInstanceOf(AppException.class).hasMessage(AppErrorMessage.ADDRESS_INVALID.getDetail()),
            () -> assertThatThrownBy(() -> service.create(42L, tooShort))
                .isInstanceOf(AppException.class).hasMessage(AppErrorMessage.ADDRESS_INVALID.getDetail())
        );
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void create_address_unsupported_province_ko() {
        // given
        when(provinceService.requireSupported("01"))
            .thenThrow(new AppException(AppErrorMessage.PROVINCE_NOT_SUPPORTED));

        // when / then
        assertThatThrownBy(() -> service.create(42L, details(false)))
            .isInstanceOf(AppException.class).hasMessage(AppErrorMessage.PROVINCE_NOT_SUPPORTED.getDetail());
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void create_address_unknown_province_ko() {
        // given
        when(provinceService.requireSupported("01")).thenThrow(new AppException(AppErrorMessage.PROVINCE_UNKNOWN));

        // when / then
        assertThatThrownBy(() -> service.create(42L, details(false)))
            .isInstanceOf(AppException.class).hasMessage(AppErrorMessage.PROVINCE_UNKNOWN.getDetail());
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void create_first_default_address_ok() {
        // given
        when(provinceService.requireSupported("01")).thenReturn(supported("01"));
        when(repository.findByUserIdAndIsDefaultTrueAndDeletedAtIsNull(42L)).thenReturn(Optional.empty());
        when(repository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

        // when
        var result = service.create(42L, details(true));
        var captured = ArgumentCaptor.forClass(CustomerAddress.class);
        verify(repository).saveAndFlush(captured.capture());

        // then
        assertAll(
            () -> assertThat(result.isDefaultAddress()).isTrue(),
            () -> assertThat(captured.getAllValues()).hasSize(1),
            () -> assertThat(captured.getValue().isIsDefault()).isTrue()
        );
    }

    @Test
    void update_address_ok() {
        // given
        var existing = stored(5L, 42L, false);
        var details = details(false);
        details.setLine1("12 Hàng Bạc");
        details.setPhone("0987654321");
        when(repository.findByAddressIdAndUserIdAndDeletedAtIsNull(5L, 42L)).thenReturn(Optional.of(existing));
        when(provinceService.requireSupported("01")).thenReturn(supported("01"));
        when(repository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

        // when
        var result = service.update(42L, 5L, details);

        // then
        assertAll(
            () -> assertThat(result.getAddressId()).isEqualTo(5L),
            () -> assertThat(existing.getLine1()).isEqualTo("12 Hàng Bạc"),
            () -> assertThat(existing.getPhone()).isEqualTo("0987654321"),
            () -> assertThat(existing.getUserId()).isEqualTo(42L),
            () -> assertThat(existing.getCreatedAt()).isEqualTo(created())
        );
    }

    @Test
    void update_address_not_found_ko() {
        // given
        when(repository.findByAddressIdAndUserIdAndDeletedAtIsNull(5L, 42L)).thenReturn(Optional.empty());

        // when / then
        assertThatThrownBy(() -> service.update(42L, 5L, details(false)))
            .isInstanceOf(AppException.class).hasMessage(AppErrorMessage.ADDRESS_NOT_FOUND.getDetail());
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void update_address_to_unsupported_province_ko() {
        // given
        var existing = stored(5L, 42L, false);
        var details = details(false);
        details.setProvinceCode("79");
        when(repository.findByAddressIdAndUserIdAndDeletedAtIsNull(5L, 42L)).thenReturn(Optional.of(existing));
        when(provinceService.requireSupported("79"))
            .thenThrow(new AppException(AppErrorMessage.PROVINCE_NOT_SUPPORTED));

        // when / then
        assertThatThrownBy(() -> service.update(42L, 5L, details))
            .isInstanceOf(AppException.class).hasMessage(AppErrorMessage.PROVINCE_NOT_SUPPORTED.getDetail());
        assertThat(existing.getProvinceCode()).isEqualTo("01");
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void delete_address_is_logical_ok() {
        // given
        var existing = stored(5L, 42L, false);
        when(repository.findByAddressIdAndUserIdAndDeletedAtIsNull(5L, 42L)).thenReturn(Optional.of(existing));

        // when
        service.delete(42L, 5L);

        // then
        assertThat(existing.getDeletedAt()).isNotNull();
        verify(repository).saveAndFlush(existing);
        verify(repository, never()).delete(any());
        verify(repository, never()).deleteById(any());
    }

    @Test
    void checkout_address_supported_ok() {
        // given
        when(repository.findByAddressIdAndUserIdAndDeletedAtIsNull(5L, 42L))
            .thenReturn(Optional.of(stored(5L, 42L, true)));
        when(provinceService.requireSupported("01")).thenReturn(supported("01"));

        // when
        var result = service.selectForCheckout(42L, 5L);

        // then
        assertAll(
            () -> assertThat(result.getSourceAddressId()).isEqualTo(5L),
            () -> assertThat(result.getRecipientName()).isEqualTo("Nguyễn Văn A"),
            () -> assertThat(result.getPhone()).isEqualTo("0912345678"),
            () -> assertThat(result.getLine1()).isEqualTo("1 Tràng Tiền"),
            () -> assertThat(result.getWard()).isEqualTo("Phường Hoàn Kiếm"),
            () -> assertThat(result.getProvinceCode()).isEqualTo("01"),
            () -> assertThat(result.getProvinceName()).isEqualTo("Thành phố Hà Nội"),
            () -> assertThat(result.getCountryCode()).isEqualTo("VN")
        );
    }

    @Test
    void checkout_address_unsupported_province_ko() {
        // given
        when(repository.findByAddressIdAndUserIdAndDeletedAtIsNull(5L, 42L))
            .thenReturn(Optional.of(stored(5L, 42L, false)));
        when(provinceService.requireSupported("01"))
            .thenThrow(new AppException(AppErrorMessage.PROVINCE_NOT_SUPPORTED));

        // when / then
        assertThatThrownBy(() -> service.selectForCheckout(42L, 5L))
            .isInstanceOf(AppException.class).hasMessage(AppErrorMessage.PROVINCE_NOT_SUPPORTED.getDetail());
    }

    private AddressDetails details(boolean defaultAddress) {
        return AddressDetails.builder()
            .withRecipientName(" Nguyễn Văn A ")
            .withPhone("0912345678")
            .withLine1("1 Tràng Tiền")
            .withWard("Phường Hoàn Kiếm")
            .withProvinceCode("01")
            .withDefaultAddress(defaultAddress)
            .build();
    }

    private CustomerAddress stored(Long addressId, long userId, boolean isDefault) {
        var entity = new CustomerAddress(userId, null, "Nguyễn Văn A", "0912345678", "1 Tràng Tiền", null,
            "Phường Hoàn Kiếm", null, "01", null, "VN", isDefault, null, created(), null);
        entity.setAddressId(addressId);

        return entity;
    }

    private OffsetDateTime created() {
        return OffsetDateTime.of(2026, 10, 1, 0, 0, 0, 0, ZoneOffset.UTC);
    }

    private Province supported(String code) {
        return Province.builder().withProvinceId(1L).withProvinceCode(code).withProvinceName("Thành phố Hà Nội")
            .withSupported(true).build();
    }
}
