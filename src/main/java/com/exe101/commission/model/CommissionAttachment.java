package com.exe101.commission.model;

import java.time.OffsetDateTime;
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
public class CommissionAttachment {
    private Long attachmentId;
    private Long commissionId;
    private Long fileId;
    private Long uploadedByUserId;
    private String originalName;
    private String contentType;
    private long byteLength;
    private OffsetDateTime createdAt;
}
