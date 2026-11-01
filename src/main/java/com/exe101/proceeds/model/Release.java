package com.exe101.proceeds.model;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

// One transfer of one item's proceeds to its Artist's own account (amendment §B.6), made by the Admin outside the
// system and recorded here. Each release carries exactly one proceeds line.
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder(setterPrefix = "with")
public class Release {
    private Long payoutId;
    private Long artistId;
    private Long proceedsId;
    private BigDecimal amount;
    private String currencyCode;
    private ReleaseStatus status;
    private String transferReference;
    private OffsetDateTime createdAt;
    private OffsetDateTime paidAt;
}
