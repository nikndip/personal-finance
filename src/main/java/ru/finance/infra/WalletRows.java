package ru.finance.infra;

import ru.finance.core.Transaction;
import ru.finance.core.TransactionType;
import ru.finance.core.Wallet;

import java.io.IOException;
import java.io.Writer;
import java.math.BigDecimal;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

final class WalletRows {
    static final List<String> HEADER = List.of("kind", "category", "date", "type", "amount");
    private static final Pattern DECIMAL = Pattern.compile("[0-9]+(?:\\.[0-9]+)?");

    private WalletRows() {
    }

    static Wallet read(List<CsvFile.Row> rows) throws IOException {
        Wallet wallet = new Wallet();
        for (CsvFile.Row row : rows) {
            if (row.field(0).equals("CATEGORY")) {
                row.requireEmpty(2, 5);
                String category = row.field(1);
                if (!category.equals(category.strip())) {
                    throw row.error("в названии категории есть пробелы по краям");
                }
                try {
                    wallet.addCategory(category);
                } catch (IllegalArgumentException failure) {
                    throw invalid(row, failure);
                }
            }
        }
        Set<String> categories = wallet.categories();
        Set<String> declaredBudgets = new HashSet<>();
        for (CsvFile.Row row : rows) {
            String kind = row.field(0);
            if (kind.equals("CATEGORY")) {
                continue;
            }
            String category = row.field(1);
            if (!categories.contains(category)) {
                throw row.error("категория не объявлена");
            }
            try {
                switch (kind) {
                    case "BUDGET" -> {
                        row.requireEmpty(2, 4);
                        if (!declaredBudgets.add(category)) {
                            throw row.error("бюджет категории уже объявлен");
                        }
                        wallet.setBudget(category, amount(row));
                    }
                    case "TRANSACTION" -> wallet.addTransaction(new Transaction(LocalDate.parse(row.field(2)),
                            TransactionType.valueOf(row.field(3)), category, amount(row)));
                    default -> throw row.error("неизвестный вид записи");
                }
            } catch (IllegalArgumentException | DateTimeException failure) {
                throw invalid(row, failure);
            }
        }
        return wallet;
    }

    private static BigDecimal amount(CsvFile.Row row) throws IOException {
        String value = row.field(4);
        if (!DECIMAL.matcher(value).matches()) {
            throw row.error("сумма должна быть записана десятичным числом с точкой в качестве разделителя");
        }
        return new BigDecimal(value);
    }

    static void write(Writer writer, Wallet wallet, String login) throws IOException {
        for (String category : wallet.categories()) {
            writeRow(writer, login, List.of("CATEGORY", category, "", "", ""));
        }
        for (var budget : wallet.budgets().entrySet()) {
            writeRow(writer, login, List.of("BUDGET", budget.getKey(), "", "", budget.getValue().toString()));
        }
        for (Transaction transaction : wallet.transactions()) {
            writeRow(writer, login, List.of("TRANSACTION", transaction.category(), transaction.date().toString(),
                    transaction.type().name(), transaction.amount().toString()));
        }
    }

    private static IOException invalid(CsvFile.Row row, RuntimeException failure) {
        IOException error = row.error("недопустимые данные кошелька (" + failure.getMessage() + ")");
        error.initCause(failure);
        return error;
    }

    private static void writeRow(Writer writer, String login, List<String> fields) throws IOException {
        if (login == null) {
            CsvFile.writeRow(writer, fields);
        } else {
            List<String> ownedFields = new ArrayList<>(fields.size() + 1);
            ownedFields.add(login);
            ownedFields.addAll(fields);
            CsvFile.writeRow(writer, ownedFields);
        }
    }
}
