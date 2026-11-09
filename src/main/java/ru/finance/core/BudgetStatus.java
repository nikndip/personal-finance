package ru.finance.core;

import java.math.BigDecimal;

public record BudgetStatus(BigDecimal limit, BigDecimal spent) {
    private static final BigDecimal WARNING_FRACTION = new BigDecimal("0.80");

    public BigDecimal remaining() {
        return limit.subtract(spent);
    }

    public boolean nearLimit() {
        return !exceeded() && spent.compareTo(limit.multiply(WARNING_FRACTION)) >= 0;
    }

    public boolean exceeded() {
        return spent.compareTo(limit) > 0;
    }
}
