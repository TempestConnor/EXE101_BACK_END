package com.exe101.customer.dto;

import java.time.OffsetDateTime;

public record CustomerDTO(Long userId, String email, String displayName, OffsetDateTime createdAt) {
}
