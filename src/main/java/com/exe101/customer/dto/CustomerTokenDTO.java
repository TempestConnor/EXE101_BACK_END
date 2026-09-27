package com.exe101.customer.dto;

public record CustomerTokenDTO(String accessToken, String tokenType, long expiresIn) {
}
