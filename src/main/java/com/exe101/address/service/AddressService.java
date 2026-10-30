package com.exe101.address.service;

import com.exe101.address.model.Address;
import com.exe101.address.model.AddressDetails;
import com.exe101.address.model.ShippingAddress;
import java.util.List;

public interface AddressService {
    List<Address> list(Long userId);

    Address create(Long userId, AddressDetails details);

    Address update(Long userId, Long addressId, AddressDetails details);

    void delete(Long userId, Long addressId);

    ShippingAddress selectForCheckout(Long userId, Long addressId);
}
