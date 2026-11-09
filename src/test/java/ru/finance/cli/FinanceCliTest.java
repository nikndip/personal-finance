package ru.finance.cli;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.finance.core.FinanceService;
import ru.finance.infra.FileAccountRepository;
import ru.finance.infra.FileWalletRepository;
import ru.finance.infra.WalletCsv;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FinanceCliTest {
    @TempDir
    Path directory;
    private Path dataDirectory;

    @BeforeEach
    void createDataDirectory() throws IOException {
        dataDirectory = Files.createDirectory(directory.resolve("data"));
    }

    @Test
    void runsCourseworkScenarioAndSavesWalletOnExit() throws IOException {
        String output = run("""
                register
                alice
                test password
                login
                alice
                test password
                category add
                Зарплата
                category add
                Премия
                category add
                Еда
                category add
                Транспорт
                income
                Зарплата
                60000
                2026-09-01
                income
                Премия
                3000
                2026-09-01
                expense
                Еда
                7500
                2026-09-01
                expense
                Транспорт
                800
                2026-09-01
                report

                2026-09-01
                2026-09-01
                exit
                """);

        assertReportTotals(output, "63000.00", "8300.00");

        try (var service = openService()) {
            assertTrue(service.login("alice", "test password".toCharArray()));
            assertEquals(4, service.wallet().transactions().size());
            assertEquals(new BigDecimal("54700.00"), service.wallet().balance());
        }
    }

    @Test
    void warnsAboutBudgetThresholdAndNegativeBalance() throws IOException {
        String output = run("""
                register
                alice
                test password
                login
                alice
                test password
                category add
                Зарплата
                category add
                Еда
                income
                Зарплата
                100
                2026-09-01
                budget
                Еда
                100
                expense
                Еда
                80
                2026-09-01
                expense
                Еда
                20
                2026-09-01
                expense
                Еда
                0.01
                2026-09-01
                exit
                """);

        assertTrue(output.contains("достигнуто 80%"), output);
        assertTrue(output.contains("нулевой баланс"), output);
        assertTrue(output.contains("превышен бюджет"), output);
        assertTrue(output.contains("расходы превысили доходы"), output);
    }

    @Test
    void writesReportWithCategoryAndDateFilters() throws IOException {
        Path reportFile = directory.resolve("финансовый отчёт.txt");
        run("""
                register
                alice
                test password
                login
                alice
                test password
                category add
                Зарплата
                category add
                Премия
                category add
                Еда
                income
                Зарплата
                37
                2026-09-01
                income
                Зарплата
                203
                2026-08-31
                income
                Премия
                101
                2026-09-01
                expense
                Еда
                15
                2026-09-01
                expense
                Еда
                19
                2026-09-02
                report file
                Зарплата
                Еда

                2026-09-01
                2026-09-01
                %s
                exit
                """.formatted(reportFile));

        String report = Files.readString(reportFile);
        assertReportTotals(report, "37.00", "15.00");
        assertFalse(report.contains("Премия"), report);
    }

    @Test
    void validatesCsvBeforeReplacingOnlyActiveWallet() throws IOException {
        Path invalid = Files.writeString(directory.resolve("invalid.csv"), """
                kind,category,date,type,amount
                CATEGORY,Лишняя,,,
                TRANSACTION,Лишняя,2026-02-30,INCOME,15.00
                """);
        Path replacement = Files.writeString(directory.resolve("replacement.csv"), """
                kind,category,date,type,amount
                CATEGORY,Подработка,,,
                TRANSACTION,Подработка,2026-09-01,INCOME,150.00
                """);
        Path exported = directory.resolve("кошелёк alice.csv");

        String output = run("""
                register
                alice
                test password
                register
                bob
                test password
                login
                bob
                test password
                category add
                Премия
                income
                Премия
                50
                2026-09-01
                login
                alice
                test password
                category add
                Зарплата
                income
                Зарплата
                100
                2026-09-01
                import
                %s
                export
                %s
                import
                %s
                да
                exit
                """.formatted(invalid, exported, replacement));

        assertTrue(output.contains("Не удалось импортировать кошелёк"), output);
        var original = WalletCsv.importWallet(exported);
        assertEquals(Set.of("Зарплата"), original.categories());
        assertEquals(new BigDecimal("100.00"), original.balance());

        try (var service = openService()) {
            assertTrue(service.login("alice", "test password".toCharArray()));
            assertEquals(Set.of("Подработка"), service.wallet().categories());
            assertEquals(new BigDecimal("150.00"), service.wallet().balance());

            assertTrue(service.login("bob", "test password".toCharArray()));
            assertEquals(Set.of("Премия"), service.wallet().categories());
            assertEquals(new BigDecimal("50.00"), service.wallet().balance());
        }
    }

    @Test
    void rejectsInvalidAmountAndSavesCompletedOperationsOnEof() throws IOException {
        String output = run("""
                register
                alice
                test password
                login
                alice
                test password
                category add
                Зарплата
                income
                Зарплата
                сто
                2026-09-01
                income
                Зарплата
                100
                2026-09-01
                expense
                Зарплата
                25
                """);

        assertTrue(output.contains("Введите положительную сумму"), output);
        try (var service = openService()) {
            assertTrue(service.login("alice", "test password".toCharArray()));
            assertEquals(1, service.wallet().transactions().size());
            assertEquals(new BigDecimal("100.00"), service.wallet().balance());
        }
    }

    private static void assertReportTotals(String report, String income, String expenses) {
        assertTrue(report.lines().anyMatch(line -> line.contains("Общий доход") && line.contains(income)), report);
        assertTrue(report.lines().anyMatch(line -> line.contains("Общие расходы") && line.contains(expenses)), report);
    }

    private String run(String commands) throws IOException {
        var output = new StringWriter();
        try (var input = new BufferedReader(new StringReader(commands));
             var writer = new PrintWriter(output)) {
            // CLI is responsible for saving on termination.
            new FinanceCli(openService(), input, writer, dataDirectory).run();
        }
        return output.toString();
    }

    private FinanceService openService() {
        return new FinanceService(new FileAccountRepository(dataDirectory.resolve("accounts.csv")),
                new FileWalletRepository(dataDirectory.resolve("wallets.csv")));
    }
}
