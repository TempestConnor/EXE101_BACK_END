package com.exe101.artist.model;

import java.math.BigDecimal;
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
public class CommissionSettings {
    private boolean acceptingCommissions;
    // Informational only (§17.3): never used as a Quote price.
    private BigDecimal startingPrice;
}
