package ru.finance.core;

import java.math.BigDecimal;
import java.time.LocalDate;

public record Transaction(LocalDate date, TransactionType type, String category, BigDecimal amount) {
    public Transaction {
        if (date == null) {
            throw new IllegalArgumentException("Укажите дату операции.");
        }
        if (type == null) {
            throw new IllegalArgumentException("Укажите тип операции.");
        }
        category = Wallet.normalizeCategory(category);
        amount = Wallet.requirePositiveAmount(amount);
    }
}
