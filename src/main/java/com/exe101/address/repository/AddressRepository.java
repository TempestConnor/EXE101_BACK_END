package com.exe101.address.repository;

import com.exe101.entity.CustomerAddress;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AddressRepository extends JpaRepository<CustomerAddress, Long> {
    List<CustomerAddress> findByUserIdAndDeletedAtIsNullOrderByAddressIdAsc(long userId);

    Optional<CustomerAddress> findByAddressIdAndUserIdAndDeletedAtIsNull(Long addressId, long userId);

    Optional<CustomerAddress> findByUserIdAndIsDefaultTrueAndDeletedAtIsNull(long userId);
}
