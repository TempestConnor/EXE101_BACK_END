package com.exe101.commission.dto;

public record DeliverableFileDTO(Long fileId, String originalName, String contentType, long byteLength) {
}
