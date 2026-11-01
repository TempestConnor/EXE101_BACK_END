package com.exe101.commission.dto;

import jakarta.validation.constraints.Size;

public record SubmitDeliverableDTO(@Size(max = 5000) String notes) {
}
