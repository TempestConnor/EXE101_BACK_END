package com.exe101.address.service;

import com.exe101.address.mapper.AddressEntityMapper;
import com.exe101.address.model.Address;
import com.exe101.address.model.AddressDetails;
import com.exe101.address.model.ShippingAddress;
import com.exe101.address.repository.AddressRepository;
import com.exe101.common.error.AppErrorMessage;
import com.exe101.common.error.AppException;
import com.exe101.common.persistence.DatabaseErrors;
import com.exe101.entity.CustomerAddress;
import com.exe101.province.model.Province;
import com.exe101.province.service.ProvinceService;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
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
public class AddressServiceImpl implements AddressService {
    private static String vietnam = "VN";
    // Vietnamese number: 0 followed by nine digits.
    private static Pattern phoneFormat = Pattern.compile("0\\d{9}");
    // Vietnamese postal code: five digits.
    private static Pattern postalCodeFormat = Pattern.compile("\\d{5}");
    private static int maxLabelLength = 80;
    private static int maxNameLength = 160;
    private static int maxLineLength = 240;
    private static int maxProvinceCodeLength = 20;
    private final AddressRepository repository;
    private final AddressEntityMapper mapper;
    private final ProvinceService provinceService;

    @Override
    public List<Address> list(Long userId) {
        return mapper.toModel(repository.findByUserIdAndDeletedAtIsNullOrderByAddressIdAsc(userId));
    }

    @Override
    public Address get(Long userId, Long addressId) {
        return mapper.toModel(requireOwned(userId, addressId));
    }

    @Override
    public Address create(Long userId, AddressDetails details) {
        AddressDetails normalized = normalize(details);
        provinceService.requireSupported(normalized.getProvinceCode());

        if (normalized.isDefaultAddress()) {
            clearDefault(userId, null);
        }

        CustomerAddress entity = new CustomerAddress();
        mapper.update(normalized, entity);
        entity.setUserId(userId);
        entity.setCreatedAt(OffsetDateTime.now(ZoneOffset.UTC).withNano(0));
        CustomerAddress saved = save(entity);
        log.info("[ADDRESS] - ACTION: create: userId: {}: addressId: {}", userId, saved.getAddressId());

        return mapper.toModel(saved);
    }

    @Override
    public Address update(Long userId, Long addressId, AddressDetails details) {
        CustomerAddress entity = requireOwned(userId, addressId);
        AddressDetails normalized = normalize(details);
        provinceService.requireSupported(normalized.getProvinceCode());

        if (normalized.isDefaultAddress()) {
            clearDefault(userId, addressId);
        }

        mapper.update(normalized, entity);
        CustomerAddress saved = save(entity);
        log.info("[ADDRESS] - ACTION: update: userId: {}: addressId: {}", userId, addressId);

        return mapper.toModel(saved);
    }

    @Override
    public void delete(Long userId, Long addressId) {
        CustomerAddress entity = requireOwned(userId, addressId);
        entity.setIsDefault(false);
        entity.setDeletedAt(OffsetDateTime.now(ZoneOffset.UTC).withNano(0));
        repository.saveAndFlush(entity);
        log.info("[ADDRESS] - ACTION: delete: userId: {}: addressId: {}", userId, addressId);
    }

    @Override
    public ShippingAddress selectForCheckout(Long userId, Long addressId) {
        CustomerAddress entity = requireOwned(userId, addressId);

        if (!vietnam.equals(entity.getCountryCode())) {
            throw new AppException(AppErrorMessage.ADDRESS_INVALID);
        }

        Province province = provinceService.requireSupported(entity.getProvinceCode());

        return mapper.toShipping(entity, province.getProvinceName());
    }

    private CustomerAddress requireOwned(Long userId, Long addressId) {
        return repository.findByAddressIdAndUserIdAndDeletedAtIsNull(addressId, userId)
            .orElseThrow(() -> new AppException(AppErrorMessage.ADDRESS_NOT_FOUND));
    }

    private void clearDefault(Long userId, Long keepAddressId) {
        Optional<CustomerAddress> current = repository.findByUserIdAndIsDefaultTrueAndDeletedAtIsNull(userId);
        boolean otherDefault = current.isPresent() && !current.get().getAddressId().equals(keepAddressId);

        if (!otherDefault) {
            return;
        }

        CustomerAddress previous = current.get();
        previous.setIsDefault(false);
        repository.saveAndFlush(previous);
    }

    private CustomerAddress save(CustomerAddress entity) {
        try {
            return repository.saveAndFlush(entity);
        } catch (DataIntegrityViolationException exception) {
            // UX_Address_Default: another request made a different address the default first.
            if (DatabaseErrors.isDuplicateKey(exception)) {
                throw new AppException(AppErrorMessage.DEFAULT_ADDRESS_CONFLICT);
            }

            throw exception;
        }
    }

    private AddressDetails normalize(AddressDetails details) {
        AddressDetails normalized = AddressDetails.builder()
            .withLabel(blankToNull(details.getLabel()))
            .withRecipientName(blankToNull(details.getRecipientName()))
            .withPhone(blankToNull(details.getPhone()))
            .withLine1(blankToNull(details.getLine1()))
            .withLine2(blankToNull(details.getLine2()))
            .withWard(blankToNull(details.getWard()))
            .withDistrict(blankToNull(details.getDistrict()))
            .withProvinceCode(blankToNull(details.getProvinceCode()))
            .withPostalCode(blankToNull(details.getPostalCode()))
            .withCountryCode(details.getCountryCode() == null ? vietnam : details.getCountryCode().strip())
            .withDefaultAddress(details.isDefaultAddress())
            .build();

        if (!isValid(normalized)) {
            throw new AppException(AppErrorMessage.ADDRESS_INVALID);
        }

        return normalized;
    }

    private boolean isValid(AddressDetails details) {
        boolean requiredPresent = details.getRecipientName() != null && details.getPhone() != null
            && details.getLine1() != null && details.getWard() != null && details.getProvinceCode() != null;

        if (!requiredPresent) {
            return false;
        }

        boolean formatsValid = phoneFormat.matcher(details.getPhone()).matches()
            && (details.getPostalCode() == null || postalCodeFormat.matcher(details.getPostalCode()).matches())
            && vietnam.equals(details.getCountryCode());
        boolean lengthsValid = fits(details.getLabel(), maxLabelLength)
            && fits(details.getRecipientName(), maxNameLength)
            && fits(details.getLine1(), maxLineLength)
            && fits(details.getLine2(), maxLineLength)
            && fits(details.getWard(), maxNameLength)
            && fits(details.getDistrict(), maxNameLength)
            && fits(details.getProvinceCode(), maxProvinceCodeLength);

        return formatsValid && lengthsValid;
    }

    private boolean fits(String value, int maxLength) {
        return value == null || value.length() <= maxLength;
    }

    private String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }

        return value.strip();
    }
}
