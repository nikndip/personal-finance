package ru.finance.core;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class Wallet {
    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2);
    private static final CategoryTotals EMPTY_TOTALS = new CategoryTotals(ZERO, ZERO);

    private final Set<String> categories = new LinkedHashSet<>();
    private final Map<String, BigDecimal> budgets = new LinkedHashMap<>();
    private final List<Transaction> transactions = new ArrayList<>();

    public Set<String> categories() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(categories));
    }

    public Map<String, BigDecimal> budgets() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(budgets));
    }

    public Map<String, BudgetStatus> budgetStatuses() {
        if (budgets.isEmpty()) {
            return Map.of();
        }

        Map<String, CategoryTotals> totals = report(budgets.keySet(), null, null).byCategory();
        Map<String, BudgetStatus> statuses = new LinkedHashMap<>();
        budgets.forEach((category, limit) ->
                statuses.put(category, new BudgetStatus(limit, totals.get(category).expenses())));
        return Collections.unmodifiableMap(statuses);
    }

    public List<Transaction> transactions() {
        return List.copyOf(transactions);
    }

    public void addCategory(String category) {
        String name = normalizeCategory(category);
        if (!categories.add(name)) {
            throw new IllegalArgumentException("Категория уже существует: " + name);
        }
    }

    public void renameCategory(String category, String newCategory) {
        String oldName = requireCategory(category);
        String newName = normalizeCategory(newCategory);
        if (oldName.equals(newName)) {
            return;
        }

        addCategory(newName);
        categories.remove(oldName);
        BigDecimal budget = budgets.remove(oldName);
        if (budget != null) {
            budgets.put(newName, budget);
        }
        transactions.replaceAll(transaction -> transaction.category().equals(oldName)
                ? new Transaction(transaction.date(), transaction.type(), newName, transaction.amount())
                : transaction);
    }

    public void setBudget(String category, BigDecimal amount) {
        budgets.put(requireCategory(category), requirePositiveAmount(amount));
    }

    public void addTransaction(Transaction transaction) {
        if (transaction == null) {
            throw new IllegalArgumentException("Укажите операцию.");
        }
        requireCategory(transaction.category());
        transactions.add(transaction);
    }

    public BigDecimal balance() {
        BigDecimal balance = ZERO;
        for (Transaction transaction : transactions) {
            balance = transaction.type() == TransactionType.INCOME
                    ? balance.add(transaction.amount())
                    : balance.subtract(transaction.amount());
        }
        return balance;
    }

    public Report report(Set<String> categoryFilter, LocalDate from, LocalDate to) {
        if (from != null && to != null && from.isAfter(to)) {
            throw new IllegalArgumentException("Начальная дата должна быть не позже конечной.");
        }

        Map<String, CategoryTotals> byCategory = new LinkedHashMap<>();
        if (categoryFilter == null || categoryFilter.isEmpty()) {
            categories.forEach(category -> byCategory.put(category, EMPTY_TOTALS));
        } else {
            categoryFilter.forEach(category -> byCategory.put(requireCategory(category), EMPTY_TOTALS));
        }

        BigDecimal income = ZERO;
        BigDecimal expenses = ZERO;
        int transactionCount = 0;
        for (Transaction transaction : transactions) {
            CategoryTotals totals = byCategory.get(transaction.category());
            if (totals == null
                    || (from != null && transaction.date().isBefore(from))
                    || (to != null && transaction.date().isAfter(to))) {
                continue;
            }

            if (transaction.type() == TransactionType.INCOME) {
                income = income.add(transaction.amount());
                byCategory.put(transaction.category(),
                        new CategoryTotals(totals.income().add(transaction.amount()), totals.expenses()));
            } else {
                expenses = expenses.add(transaction.amount());
                byCategory.put(transaction.category(),
                        new CategoryTotals(totals.income(), totals.expenses().add(transaction.amount())));
            }
            transactionCount++;
        }
        return new Report(income, expenses, byCategory, transactionCount);
    }

    private String requireCategory(String category) {
        String name = normalizeCategory(category);
        if (!categories.contains(name)) {
            throw new IllegalArgumentException("Неизвестная категория: " + name);
        }
        return name;
    }

    static String normalizeCategory(String category) {
        if (category == null || category.isBlank() || category.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Введите непустое имя категории без управляющих символов.");
        }
        return category.strip();
    }

    static BigDecimal requirePositiveAmount(BigDecimal amount) {
        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException("Сумма должна быть больше нуля.");
        }
        try {
            return amount.setScale(2, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("Сумма должна содержать не больше двух знаков после запятой.");
        }
    }
}
