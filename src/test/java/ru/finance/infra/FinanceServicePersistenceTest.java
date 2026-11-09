package ru.finance.infra;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.finance.core.FinanceService;
import ru.finance.core.Transaction;
import ru.finance.core.TransactionType;
import ru.finance.core.Wallet;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FinanceServicePersistenceTest {
    private static final LocalDate DATE = LocalDate.of(2026, 9, 1);

    @TempDir
    Path directory;

    @Test
    void authenticatesRegisteredUserAndRejectsWrongPasswordAfterRestart() throws IOException {
        try (var service = openService()) {
            service.register("alice", password());
            assertThrows(IllegalArgumentException.class, () -> service.register("alice", password()));
        }

        try (var service = openService()) {
            assertFalse(service.login("alice", "different password".toCharArray()));
            assertFalse(service.login("nobody", password()));
            assertNull(service.currentLogin());
            assertThrows(IllegalStateException.class, service::wallet);
            assertTrue(service.login("alice", password()));
            assertEquals("alice", service.currentLogin());
        }
    }

    @Test
    void rejectsLoginsThatCouldEscapeAccountStorage() throws IOException {
        try (var service = openService()) {
            assertThrows(IllegalArgumentException.class, () -> service.register("../alice", password()));
            assertThrows(IllegalArgumentException.class, () -> service.register("alice/bob", password()));
        }
    }

    @Test
    void savesWalletOnLogoutAndKeepsAccountsIsolated() throws IOException {
        try (var service = openService()) {
            service.register("alice", password());
            service.register("bob", password());
            assertTrue(service.login("alice", password()));
            addSalary(service.wallet(), "100.25");
            service.wallet().setBudget("Зарплата", new BigDecimal("75.15"));
            service.logout();
            assertNull(service.currentLogin());

            assertTrue(service.login("bob", password()));
            assertTrue(service.wallet().transactions().isEmpty());
            service.wallet().addCategory("Еда");
            service.logout();
        }

        try (var service = openService()) {
            assertTrue(service.login("alice", password()));
            assertEquals(Set.of("Зарплата"), service.wallet().categories());
            assertAmount("75.15", service.wallet().budgets().get("Зарплата"));
            assertAmount("100.25", service.wallet().balance());
            service.logout();

            assertTrue(service.login("bob", password()));
            assertEquals(Set.of("Еда"), service.wallet().categories());
            assertAmount("0", service.wallet().balance());
        }
    }

    @Test
    void savesWalletWhenServiceClosesWithAnActiveUser() throws IOException {
        try (var service = openService()) {
            service.register("alice", password());
            assertTrue(service.login("alice", password()));
            addSalary(service.wallet(), "10.25");
        }

        try (var service = openService()) {
            assertTrue(service.login("alice", password()));
            assertAmount("10.25", service.wallet().balance());
            assertEquals(1, service.wallet().transactions().size());
        }
    }

    @Test
    void transfersMoneyBetweenPersistedWallets() throws IOException {
        try (var service = openService()) {
            service.register("alice", password());
            service.register("bob", password());
            assertTrue(service.login("bob", password()));
            addSalary(service.wallet(), "50");
            service.logout();
            assertTrue(service.login("alice", password()));
            addSalary(service.wallet(), "100");

            service.transfer("bob", new BigDecimal("25.75"), DATE.plusDays(1));
            assertAmount("74.25", service.wallet().balance());
        }

        try (var service = openService()) {
            assertTrue(service.login("alice", password()));
            assertAmount("74.25", service.wallet().balance());
            assertTransfer(service.wallet(), TransactionType.EXPENSE);
            service.logout();

            assertTrue(service.login("bob", password()));
            assertAmount("75.75", service.wallet().balance());
            assertTransfer(service.wallet(), TransactionType.INCOME);
        }
    }

    @Test
    void rejectsInvalidTransfersWithoutChangingEitherWallet() throws IOException {
        try (var service = openService()) {
            service.register("alice", password());
            service.register("bob", password());
            assertTrue(service.login("alice", password()));
            addSalary(service.wallet(), "100");

            assertThrows(IllegalArgumentException.class,
                    () -> service.transfer("alice", new BigDecimal("20"), DATE));
            assertThrows(IllegalArgumentException.class,
                    () -> service.transfer("nobody", new BigDecimal("20"), DATE));
            assertThrows(IllegalArgumentException.class,
                    () -> service.transfer("bob", new BigDecimal("100.01"), DATE));
            assertThrows(IllegalArgumentException.class,
                    () -> service.transfer("bob", new BigDecimal("0"), DATE));

            assertAmount("100", service.wallet().balance());
            assertEquals(1, service.wallet().transactions().size());
            service.logout();
            assertTrue(service.login("bob", password()));
            assertTrue(service.wallet().transactions().isEmpty());
        }
    }

    @Test
    void failedSaveKeepsActiveWalletAvailableForRetry() throws IOException {
        Path walletFile = directory.resolve("wallets.csv");
        try (var service = openService()) {
            service.register("alice", password());
            assertTrue(service.login("alice", password()));
            addSalary(service.wallet(), "10.25");
            Files.createDirectory(walletFile);

            assertThrows(IOException.class, service::logout);
            assertEquals("alice", service.currentLogin());
            assertAmount("10.25", service.wallet().balance());

            Files.delete(walletFile);
            service.logout();
        }

        try (var service = openService()) {
            assertTrue(service.login("alice", password()));
            assertAmount("10.25", service.wallet().balance());
        }
    }

    private FinanceService openService() {
        return new FinanceService(new FileAccountRepository(directory.resolve("accounts.csv")),
                new FileWalletRepository(directory.resolve("wallets.csv")));
    }

    private static char[] password() {
        return "test password".toCharArray();
    }

    private static void addSalary(Wallet wallet, String amount) {
        wallet.addCategory("Зарплата");
        wallet.addTransaction(new Transaction(DATE, TransactionType.INCOME, "Зарплата", new BigDecimal(amount)));
    }

    private static void assertTransfer(Wallet wallet, TransactionType type) {
        assertEquals(2, wallet.transactions().size());
        var transfer = wallet.transactions().get(1);
        assertEquals(DATE.plusDays(1), transfer.date());
        assertEquals(type, transfer.type());
        assertEquals(FinanceService.TRANSFER_CATEGORY, transfer.category());
        assertAmount("25.75", transfer.amount());
    }

    private static void assertAmount(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual), () -> "Получено " + actual);
    }
}
