package ru.finance.core;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WalletTest {
    private static final LocalDate START = LocalDate.of(2026, 9, 1);
    private final Wallet wallet = new Wallet();

    @Test
    void totalsMatchCourseworkExample() {
        for (String category : List.of("Зарплата", "Бонус", "Еда", "Развлечения", "Коммунальные услуги", "Такси")) {
            wallet.addCategory(category);
        }
        wallet.setBudget("Еда", new BigDecimal("4000"));
        wallet.setBudget("Развлечения", new BigDecimal("3000"));
        wallet.setBudget("Коммунальные услуги", new BigDecimal("2500"));
        add(TransactionType.INCOME, "Зарплата", "20000", START);
        add(TransactionType.INCOME, "Зарплата", "40000", START);
        add(TransactionType.INCOME, "Бонус", "3000", START);
        add(TransactionType.EXPENSE, "Еда", "300", START);
        add(TransactionType.EXPENSE, "Еда", "500", START);
        add(TransactionType.EXPENSE, "Развлечения", "3000", START);
        add(TransactionType.EXPENSE, "Коммунальные услуги", "3000", START);
        add(TransactionType.EXPENSE, "Такси", "1500", START);

        var report = wallet.report(Set.of(), null, null);
        var budgets = wallet.budgetStatuses();
        assertAmount("63000", report.income());
        assertAmount("8300", report.expenses());
        assertAmount("54700", wallet.balance());
        assertAmount("60000", report.byCategory().get("Зарплата").income());
        assertAmount("800", report.byCategory().get("Еда").expenses());
        assertAmount("3200", budgets.get("Еда").remaining());
        assertAmount("0", budgets.get("Развлечения").remaining());
        assertAmount("-500", budgets.get("Коммунальные услуги").remaining());
        assertEquals(8, report.transactionCount());
    }

    @Test
    void totalsUseDecimalArithmetic() {
        wallet.addCategory("Еда");
        add(TransactionType.INCOME, "Еда", "0.30", START);
        add(TransactionType.EXPENSE, "Еда", "0.10", START);
        add(TransactionType.EXPENSE, "Еда", "0.20", START);

        var report = wallet.report(Set.of(), null, null);
        assertAmount("0.30", report.income());
        assertAmount("0.30", report.expenses());
        assertAmount("0", wallet.balance());
    }

    @Test
    void renamingCategoryPreservesTransactionsAndBudget() {
        wallet.addCategory("Продукты");
        wallet.setBudget("Продукты", new BigDecimal("1000"));
        add(TransactionType.EXPENSE, "Продукты", "300", START);
        add(TransactionType.INCOME, "Продукты", "50", START.plusDays(1));

        wallet.renameCategory("Продукты", "Еда");

        assertEquals(Set.of("Еда"), wallet.categories());
        assertEquals(Set.of("Еда"), wallet.budgets().keySet());
        assertAmount("1000", wallet.budgets().get("Еда"));
        assertEquals(List.of(
                new Transaction(START, TransactionType.EXPENSE, "Еда", new BigDecimal("300")),
                new Transaction(START.plusDays(1), TransactionType.INCOME, "Еда", new BigDecimal("50"))
        ), wallet.transactions());
        assertAmount("300", wallet.report(Set.of("Еда"), null, null).expenses());
    }

    @Test
    void changingBudgetUpdatesExistingLimit() {
        wallet.addCategory("Еда");
        wallet.setBudget("Еда", new BigDecimal("1000"));
        wallet.setBudget("Еда", new BigDecimal("900.50"));

        assertAmount("900.50", wallet.budgets().get("Еда"));
        assertEquals(1, wallet.budgets().size());
    }

    @Test
    void budgetStatusUsesExpensesAndMarksThresholds() {
        wallet.addCategory("Еда");
        wallet.setBudget("Еда", new BigDecimal("100"));
        add(TransactionType.INCOME, "Еда", "1000", START);
        add(TransactionType.EXPENSE, "Еда", "79.99", START);

        assertBudgetStatus("20.01", false, false);
        add(TransactionType.EXPENSE, "Еда", "0.01", START);
        assertBudgetStatus("20", true, false);
        add(TransactionType.EXPENSE, "Еда", "20", START);
        assertBudgetStatus("0", true, false);
        add(TransactionType.EXPENSE, "Еда", "0.01", START);
        assertBudgetStatus("-0.01", false, true);
    }

    @Test
    void filtersByMultipleCategoriesAndIncludesBothDateBoundaries() {
        wallet.addCategory("Зарплата");
        wallet.addCategory("Еда");
        wallet.addCategory("Транспорт");
        add(TransactionType.INCOME, "Зарплата", "100", START.minusDays(1));
        add(TransactionType.INCOME, "Зарплата", "200", START);
        add(TransactionType.EXPENSE, "Транспорт", "30", START.plusDays(1));
        add(TransactionType.EXPENSE, "Еда", "40", START.plusDays(2));
        add(TransactionType.EXPENSE, "Еда", "50", START.plusDays(3));

        var report = wallet.report(Set.of("Зарплата", "Еда"), START, START.plusDays(2));

        assertEquals(2, report.transactionCount());
        assertEquals(Set.of("Зарплата", "Еда"), report.byCategory().keySet());
        assertAmount("200", report.income());
        assertAmount("40", report.expenses());
        assertAmount("200", report.byCategory().get("Зарплата").income());
        assertAmount("40", report.byCategory().get("Еда").expenses());
    }

    @Test
    void appliesDateFiltersWithOneOpenBound() {
        wallet.addCategory("Еда");
        add(TransactionType.EXPENSE, "Еда", "10", START.minusDays(1));
        add(TransactionType.EXPENSE, "Еда", "20", START);
        add(TransactionType.EXPENSE, "Еда", "30", START.plusDays(1));

        assertAmount("30", wallet.report(Set.of(), null, START).expenses());
        assertAmount("50", wallet.report(Set.of(), START, null).expenses());
    }

    @Test
    void rejectsUnregisteredCategories() {
        assertThrows(IllegalArgumentException.class,
                () -> add(TransactionType.EXPENSE, "Еда", "100", START));
        assertThrows(IllegalArgumentException.class,
                () -> wallet.setBudget("Еда", new BigDecimal("100")));
        assertThrows(IllegalArgumentException.class,
                () -> wallet.report(Set.of("Еда"), null, null));
    }

    @Test
    void rejectsInvalidAmountsBeforeChangingWallet() {
        wallet.addCategory("Еда");
        wallet.setBudget("Еда", new BigDecimal("500"));

        for (String invalid : List.of("0", "-1", "1.001")) {
            assertThrows(IllegalArgumentException.class,
                    () -> add(TransactionType.EXPENSE, "Еда", invalid, START), invalid);
            assertThrows(IllegalArgumentException.class,
                    () -> wallet.setBudget("Еда", new BigDecimal(invalid)), invalid);
        }

        assertEquals(List.of(), wallet.transactions());
        assertAmount("500", wallet.budgets().get("Еда"));
    }

    @Test
    void trimsCategoryNamesAndRejectsDuplicates() {
        wallet.addCategory("  Еда  ");
        wallet.addCategory("еда");

        assertEquals(Set.of("Еда", "еда"), wallet.categories());
        assertThrows(IllegalArgumentException.class, () -> wallet.addCategory("Еда"));
        assertThrows(IllegalArgumentException.class, () -> wallet.addCategory("  "));
        assertThrows(IllegalArgumentException.class, () -> wallet.renameCategory("еда", "Еда"));
        assertEquals(Set.of("Еда", "еда"), wallet.categories());
    }

    private void assertBudgetStatus(String remaining, boolean nearLimit, boolean exceeded) {
        var status = wallet.budgetStatuses().get("Еда");
        assertAmount(remaining, status.remaining());
        assertEquals(nearLimit, status.nearLimit());
        assertEquals(exceeded, status.exceeded());
    }

    private void add(TransactionType type, String category, String amount, LocalDate date) {
        wallet.addTransaction(new Transaction(date, type, category, new BigDecimal(amount)));
    }

    private static void assertAmount(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual), () -> "Получено " + actual);
    }
}
