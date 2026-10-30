package com.exe101.address.controller;

import com.exe101.address.dto.AddressDTO;
import com.exe101.address.dto.CreateAddressDTO;
import com.exe101.address.dto.UpdateAddressDTO;
import com.exe101.address.mapper.AddressMapper;
import com.exe101.address.service.AddressService;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/addresses")
@RequiredArgsConstructor
public class AddressController {
    private final AddressService addressService;
    private final AddressMapper mapper;

    @GetMapping
    public List<AddressDTO> list(@AuthenticationPrincipal Long userId) {
        var addresses = addressService.list(userId);
        var response = mapper.toDto(addresses);

        return response;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AddressDTO create(@RequestBody @Valid CreateAddressDTO dto, @AuthenticationPrincipal Long userId) {
        var details = mapper.toModel(dto);
        var created = addressService.create(userId, details);
        var response = mapper.toDto(created);

        return response;
    }

    @PutMapping("/{addressId}")
    public AddressDTO update(
        @PathVariable Long addressId, @RequestBody @Valid UpdateAddressDTO dto, @AuthenticationPrincipal Long userId
    ) {
        var details = mapper.toModel(dto);
        var updated = addressService.update(userId, addressId, details);
        var response = mapper.toDto(updated);

        return response;
    }

    @DeleteMapping("/{addressId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long addressId, @AuthenticationPrincipal Long userId) {
        addressService.delete(userId, addressId);
    }
}
