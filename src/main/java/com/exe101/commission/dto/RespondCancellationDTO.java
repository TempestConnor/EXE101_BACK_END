package com.exe101.commission.dto;

import jakarta.validation.constraints.Size;

public record RespondCancellationDTO(@Size(max = 5000) String note) {
}
