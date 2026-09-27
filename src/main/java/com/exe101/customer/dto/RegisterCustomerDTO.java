package com.exe101.customer.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record RegisterCustomerDTO(
    @NotBlank @Email @Size(max = 320) String email,
    @NotNull @Size(min = 12, max = 128) String password,
    @NotBlank @Size(max = 160) String displayName
) {
}
