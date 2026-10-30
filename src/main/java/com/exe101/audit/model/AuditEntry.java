package com.exe101.audit.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder(setterPrefix = "with")
public class AuditEntry {
    private Long staffId;
    private String actionType;
    private String targetType;
    private Long targetId;
    private String reason;
    private String details;
}
