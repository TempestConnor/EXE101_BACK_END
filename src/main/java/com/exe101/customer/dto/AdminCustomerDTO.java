package com.exe101.customer.dto;

import java.time.OffsetDateTime;

// What the Admin needs to grant an Artist Profile: the account's ID and whether it is active.
public record AdminCustomerDTO(Long userId, String email, String displayName, String status,
    OffsetDateTime createdAt) {
}
