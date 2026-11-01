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
public class CommissionMessage {
    private Long messageId;
    private Long commissionId;
    private Long senderUserId;
    private String body;
    private OffsetDateTime sentAt;
}
