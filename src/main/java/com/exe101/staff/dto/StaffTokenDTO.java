package com.exe101.staff.dto;

public record StaffTokenDTO(String accessToken, String tokenType, long expiresIn) {
}
