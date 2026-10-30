package com.exe101.address.repository;

import com.exe101.address.model.AddressDetails;
import com.exe101.address.service.AddressService;
import com.exe101.common.error.AppErrorMessage;
import com.exe101.common.error.AppException;
import com.exe101.customer.model.CustomerRegistration;
import com.exe101.customer.service.CustomerService;
import com.exe101.entity.CustomerAddress;
import com.exe101.entity.SupportedProvince;
import com.exe101.province.repository.ProvinceRepository;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertAll;

@SpringBootTest
@EnabledIfEnvironmentVariable(named = "DB_URL", matches = ".+")
class AddressPersistenceTest {
    @Autowired
    private AddressService service;
    @Autowired
    private AddressRepository repository;
    @Autowired
    private ProvinceRepository provinceRepository;
    @Autowired
    private CustomerService customerService;
    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    void set_default_replaces_previous_default_ok() {
        // given
        var transaction = new TransactionTemplate(transactionManager);

        // when / then
        transaction.executeWithoutResult(status -> {
            var province = supportedProvince();
            var userId = newCustomer();
            var first = service.create(userId, details(province, true));
            var second = service.create(userId, details(province, false));
            service.update(userId, second.getAddressId(), details(province, true));
            assertAll(
                () -> assertThat(repository.findById(first.getAddressId()).orElseThrow().isIsDefault()).isFalse(),
                () -> assertThat(repository.findById(second.getAddressId()).orElseThrow().isIsDefault()).isTrue(),
                () -> assertThat(repository.findByUserIdAndIsDefaultTrueAndDeletedAtIsNull(userId).orElseThrow()
                    .getAddressId()).isEqualTo(second.getAddressId())
            );
            status.setRollbackOnly();
        });
    }

    @Test
    void list_addresses_excludes_deleted_ok() {
        // given
        var transaction = new TransactionTemplate(transactionManager);

        // when / then
        transaction.executeWithoutResult(status -> {
            var province = supportedProvince();
            var userId = newCustomer();
            var kept = service.create(userId, details(province, false));
            var removed = service.create(userId, details(province, false));
            service.delete(userId, removed.getAddressId());
            var listed = service.list(userId);
            assertAll(
                () -> assertThat(listed).hasSize(1),
                () -> assertThat(listed.getFirst().getAddressId()).isEqualTo(kept.getAddressId())
            );
            status.setRollbackOnly();
        });
    }

    @Test
    void list_addresses_only_own_ok() {
        // given
        var transaction = new TransactionTemplate(transactionManager);

        // when / then
        transaction.executeWithoutResult(status -> {
            var province = supportedProvince();
            var owner = newCustomer();
            var other = newCustomer();
            var own = service.create(owner, details(province, false));
            service.create(other, details(province, false));
            var listed = service.list(owner);
            assertAll(
                () -> assertThat(listed).hasSize(1),
                () -> assertThat(listed.getFirst().getAddressId()).isEqualTo(own.getAddressId())
            );
            status.setRollbackOnly();
        });
    }

    @Test
    void update_address_of_other_customer_ko() {
        // given
        var transaction = new TransactionTemplate(transactionManager);

        // when / then
        transaction.executeWithoutResult(status -> {
            var province = supportedProvince();
            var owner = newCustomer();
            var intruder = newCustomer();
            var address = service.create(owner, details(province, false));
            var change = details(province, false);
            change.setLine1("99 Changed Street");
            assertThatThrownBy(() -> service.update(intruder, address.getAddressId(), change))
                .isInstanceOf(AppException.class).hasMessage(AppErrorMessage.ADDRESS_NOT_FOUND.getDetail());
            assertThat(repository.findById(address.getAddressId()).orElseThrow().getLine1())
                .isEqualTo("1 Tràng Tiền");
            status.setRollbackOnly();
        });
    }

    @Test
    void update_deleted_address_ko() {
        // given
        var transaction = new TransactionTemplate(transactionManager);

        // when / then
        transaction.executeWithoutResult(status -> {
            var province = supportedProvince();
            var userId = newCustomer();
            var address = service.create(userId, details(province, false));
            service.delete(userId, address.getAddressId());
            var change = details(province, false);
            change.setLine1("99 Changed Street");
            assertThatThrownBy(() -> service.update(userId, address.getAddressId(), change))
                .isInstanceOf(AppException.class).hasMessage(AppErrorMessage.ADDRESS_NOT_FOUND.getDetail());
            assertThat(repository.findById(address.getAddressId()).orElseThrow().getLine1())
                .isEqualTo("1 Tràng Tiền");
            status.setRollbackOnly();
        });
    }

    @Test
    void delete_address_of_other_customer_ko() {
        // given
        var transaction = new TransactionTemplate(transactionManager);

        // when / then
        transaction.executeWithoutResult(status -> {
            var province = supportedProvince();
            var owner = newCustomer();
            var intruder = newCustomer();
            var address = service.create(owner, details(province, false));
            assertThatThrownBy(() -> service.delete(intruder, address.getAddressId()))
                .isInstanceOf(AppException.class).hasMessage(AppErrorMessage.ADDRESS_NOT_FOUND.getDetail());
            assertThat(repository.findById(address.getAddressId()).orElseThrow().getDeletedAt()).isNull();
            status.setRollbackOnly();
        });
    }

    @Test
    void delete_default_address_leaves_no_default_ok() {
        // given
        var transaction = new TransactionTemplate(transactionManager);

        // when / then
        transaction.executeWithoutResult(status -> {
            var province = supportedProvince();
            var userId = newCustomer();
            var defaultAddress = service.create(userId, details(province, true));
            var other = service.create(userId, details(province, false));
            service.delete(userId, defaultAddress.getAddressId());
            assertAll(
                () -> assertThat(repository.findByUserIdAndIsDefaultTrueAndDeletedAtIsNull(userId)).isEmpty(),
                () -> assertThat(repository.findById(other.getAddressId()).orElseThrow().isIsDefault()).isFalse(),
                () -> assertThat(repository.findById(defaultAddress.getAddressId())).isPresent()
            );
            status.setRollbackOnly();
        });
    }

    @Test
    void checkout_address_support_withdrawn_after_save_ko() {
        // given
        var transaction = new TransactionTemplate(transactionManager);

        // when / then
        transaction.executeWithoutResult(status -> {
            var province = supportedProvince();
            var userId = newCustomer();
            var address = service.create(userId, details(province, false));
            province.setIsSupported(false);
            provinceRepository.saveAndFlush(province);
            assertThatThrownBy(() -> service.selectForCheckout(userId, address.getAddressId()))
                .isInstanceOf(AppException.class).hasMessage(AppErrorMessage.PROVINCE_NOT_SUPPORTED.getDetail());
            status.setRollbackOnly();
        });
    }

    @Test
    void checkout_address_of_other_customer_ko() {
        // given
        var transaction = new TransactionTemplate(transactionManager);

        // when / then
        transaction.executeWithoutResult(status -> {
            var province = supportedProvince();
            var owner = newCustomer();
            var buyer = newCustomer();
            var address = service.create(owner, details(province, false));
            assertThatThrownBy(() -> service.selectForCheckout(buyer, address.getAddressId()))
                .isInstanceOf(AppException.class).hasMessage(AppErrorMessage.ADDRESS_NOT_FOUND.getDetail());
            status.setRollbackOnly();
        });
    }

    @Test
    void checkout_address_deleted_ko() {
        // given
        var transaction = new TransactionTemplate(transactionManager);

        // when / then
        transaction.executeWithoutResult(status -> {
            var province = supportedProvince();
            var userId = newCustomer();
            var address = service.create(userId, details(province, false));
            service.delete(userId, address.getAddressId());
            assertThatThrownBy(() -> service.selectForCheckout(userId, address.getAddressId()))
                .isInstanceOf(AppException.class).hasMessage(AppErrorMessage.ADDRESS_NOT_FOUND.getDetail());
            status.setRollbackOnly();
        });
    }

    @Test
    void active_default_unique_per_user_ko() {
        // given
        var transaction = new TransactionTemplate(transactionManager);

        // when / then
        transaction.executeWithoutResult(status -> {
            var province = supportedProvince();
            var userId = newCustomer();
            repository.saveAndFlush(defaultRow(userId, province));
            assertThatThrownBy(() -> repository.saveAndFlush(defaultRow(userId, province)))
                .isInstanceOf(DataIntegrityViolationException.class);
            status.setRollbackOnly();
        });
    }

    private CustomerAddress defaultRow(Long userId, SupportedProvince province) {
        return new CustomerAddress(userId, "Nguyễn Văn A", "0912345678", "1 Tràng Tiền",
            province.getProvinceCode(), "VN", true, now(), null);
    }

    private AddressDetails details(SupportedProvince province, boolean defaultAddress) {
        return AddressDetails.builder()
            .withRecipientName("Nguyễn Văn A")
            .withPhone("0912345678")
            .withLine1("1 Tràng Tiền")
            .withWard("Phường Hoàn Kiếm")
            .withProvinceCode(province.getProvinceCode())
            .withDefaultAddress(defaultAddress)
            .build();
    }

    private SupportedProvince supportedProvince() {
        // A test-only code outside the official two-digit range, so it never collides with real rows.
        var code = "T" + ThreadLocalRandom.current().nextInt(10_000_000, 100_000_000);

        return provinceRepository.saveAndFlush(new SupportedProvince(code, "Test Province", true, now()));
    }

    private Long newCustomer() {
        return customerService.register(new CustomerRegistration(
            "f04-test-" + UUID.randomUUID() + "@example.invalid", "long-password", "Address Test")).getUserId();
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(ZoneOffset.UTC).withNano(0);
    }
}
