package ru.finance.infra;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.finance.core.Transaction;
import ru.finance.core.TransactionType;
import ru.finance.core.Wallet;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WalletCsvTest {
    @TempDir
    Path directory;

    @Test
    void roundTripsCyrillicCategoriesWithCommasAndQuotes() throws IOException {
        String category = "Еда, \"дом\"";
        Wallet wallet = new Wallet();
        wallet.addCategory(category);
        wallet.addCategory("Транспорт");
        wallet.setBudget(category, new BigDecimal("850.75"));
        wallet.addTransaction(new Transaction(LocalDate.of(2026, 9, 1), TransactionType.EXPENSE,
                category, new BigDecimal("20.15")));

        Path csv = directory.resolve("wallet.csv");
        WalletCsv.exportWallet(csv, wallet);
        assertTrue(Files.readString(csv, StandardCharsets.UTF_8).contains("\"Еда, \"\"дом\"\"\""));
        Wallet restored = WalletCsv.importWallet(csv);

        assertEquals(wallet.categories(), restored.categories());
        assertEquals(0, wallet.budgets().get(category).compareTo(restored.budgets().get(category)));
        assertEquals(wallet.transactions(), restored.transactions());
    }

    @Test
    void rejectsUnterminatedQuotedField() throws IOException {
        Path csv = writeCsv("""
                kind,category,date,type,amount
                CATEGORY,"Еда,,,
                """);

        assertThrows(IOException.class, () -> WalletCsv.importWallet(csv));
    }

    @Test
    void rejectsTransactionWithUndeclaredCategory() throws IOException {
        Path csv = writeCsv("""
                kind,category,date,type,amount
                TRANSACTION,Еда,2026-09-01,EXPENSE,20.15
                """);

        assertThrows(IOException.class, () -> WalletCsv.importWallet(csv));
    }

    @Test
    void rejectsTransactionWithInvalidDate() throws IOException {
        Path csv = writeCsv("""
                kind,category,date,type,amount
                CATEGORY,Еда,,,
                TRANSACTION,Еда,2026-02-30,EXPENSE,20.15
                """);

        assertThrows(IOException.class, () -> WalletCsv.importWallet(csv));
    }

    @Test
    void exportDoesNotOverwriteExistingFile() throws IOException {
        Path csv = writeCsv("existing file");

        assertThrows(IOException.class, () -> WalletCsv.exportWallet(csv, new Wallet()));
        assertEquals("existing file", Files.readString(csv));
    }

    private Path writeCsv(String text) throws IOException {
        return Files.writeString(directory.resolve("wallet.csv"), text, StandardCharsets.UTF_8);
    }
}
