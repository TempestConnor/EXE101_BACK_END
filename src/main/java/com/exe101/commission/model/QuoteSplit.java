package com.exe101.commission.model;

import java.math.BigDecimal;
import java.math.RoundingMode;
import lombok.AllArgsConstructor;
import lombok.Getter;

// The two installments of a Quote (amendment §D.13). Price and tax are each split, the first half rounded up to whole
// VND (approved 2 November 2026), so each installment carries its exact proceeds and tax, and the two installments
// add up to the Quote total.
@Getter
@AllArgsConstructor
public class QuoteSplit {
    private static int moneyScale = 4;
    private BigDecimal firstPrice;
    private BigDecimal firstTax;
    private BigDecimal finalPrice;
    private BigDecimal finalTax;

    public static QuoteSplit of(BigDecimal price, BigDecimal tax) {
        BigDecimal firstPrice = firstHalf(price);
        BigDecimal firstTax = firstHalf(tax);

        return new QuoteSplit(firstPrice, firstTax, price.subtract(firstPrice).setScale(moneyScale),
            tax.subtract(firstTax).setScale(moneyScale));
    }

    public BigDecimal getFirstAmount() {
        return firstPrice.add(firstTax);
    }

    public BigDecimal getFinalAmount() {
        return finalPrice.add(finalTax);
    }

    private static BigDecimal firstHalf(BigDecimal amount) {
        return amount.divide(BigDecimal.TWO, 0, RoundingMode.CEILING).setScale(moneyScale);
    }
}
