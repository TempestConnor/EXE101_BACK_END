package com.exe101.order.model;

import java.math.BigDecimal;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

// What the Customer confirmed from the last checkout preview; compared with the amounts recomputed under lock.
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder(setterPrefix = "with")
public class OrderCommit {
    private byte[] cartVersion;
    private Long addressId;
    private List<CommitLine> lines;
    private BigDecimal totalAmount;
}
