package ru.finance.core;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public record Report(
        BigDecimal income,
        BigDecimal expenses,
        Map<String, CategoryTotals> byCategory,
        int transactionCount) {

    public Report {
        byCategory = Collections.unmodifiableMap(new LinkedHashMap<>(byCategory));
    }
}
