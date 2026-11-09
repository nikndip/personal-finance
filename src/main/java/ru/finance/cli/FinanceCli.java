package ru.finance.cli;

import ru.finance.core.BudgetStatus;
import ru.finance.core.FinanceService;
import ru.finance.core.Report;
import ru.finance.core.Transaction;
import ru.finance.core.TransactionType;
import ru.finance.core.Wallet;
import ru.finance.infra.WalletCsv;

import java.io.BufferedReader;
import java.io.Console;
import java.io.IOError;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
import static java.nio.file.StandardOpenOption.CREATE_NEW;
import static java.nio.file.StandardOpenOption.WRITE;

public final class FinanceCli {
    private static final Pattern AMOUNT = Pattern.compile("[0-9]+([.,][0-9]{1,2})?");
    private static final Pattern DATE = Pattern.compile("[0-9]{4}-[0-9]{2}-[0-9]{2}");
    private static final List<String> SERVICE_FILES = List.of("accounts.csv", "wallets.csv", "finance.lock");

    private final FinanceService service;
    private final BufferedReader input;
    private final PrintWriter output;
    private final Path dataDirectory;
    private final Console console;

    public FinanceCli(FinanceService service, BufferedReader input, PrintWriter output, Path dataDirectory) {
        this(service, input, output, dataDirectory, null);
    }

    public FinanceCli(FinanceService service, Console console, Path dataDirectory) {
        this(service, null, console.writer(), dataDirectory, console);
    }

    private FinanceCli(FinanceService service, BufferedReader input, PrintWriter output, Path dataDirectory,
                       Console console) {
        this.service = service;
        this.input = input;
        this.output = output;
        this.dataDirectory = dataDirectory.toAbsolutePath().normalize();
        this.console = console;
    }

    public void run() throws IOException {
        output.println("Система управления личными финансами");
        output.println("Введите help для списка команд.");
        try {
            runCommands();
        } catch (InputStopped stopped) {
            output.println();
            try {
                service.close();
            } catch (IOException saveFailure) {
                if (stopped.failure == null) {
                    throw saveFailure;
                }
                stopped.failure.addSuppressed(saveFailure);
            }
            if (stopped.failure != null) {
                throw stopped.failure;
            }
            output.println("До свидания.");
        } finally {
            output.flush();
        }
    }

    private void runCommands() throws InputStopped {
        while (true) {
            String login = service.currentLogin();
            String prompt = login == null ? "> " : login + "> ";
            String command = read(prompt).strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
            try {
                switch (command) {
                    case "" -> { }
                    case "help" -> help();
                    case "register" -> credentials(true);
                    case "login" -> credentials(false);
                    case "logout" -> logout();
                    case "exit" -> {
                        service.close();
                        output.println("До свидания.");
                        return;
                    }
                    case "categories" -> categories();
                    case "category add" -> addCategory();
                    case "category rename" -> renameCategory();
                    case "income" -> record(TransactionType.INCOME);
                    case "expense" -> record(TransactionType.EXPENSE);
                    case "budget" -> budget();
                    case "report" -> report(false);
                    case "report file" -> report(true);
                    case "export" -> exportWallet();
                    case "import" -> importWallet();
                    case "transfer" -> transfer();
                    default -> output.println("Неизвестная команда. Введите help для списка команд.");
                }
            } catch (IOException | IllegalArgumentException | IllegalStateException failure) {
                output.println("Ошибка: " + failure.getMessage());
            }
        }
    }

    private void help() {
        output.print("""
                help             показать список команд
                register         создать аккаунт
                login            войти в аккаунт
                logout           сохранить кошелёк и выйти из аккаунта
                exit             сохранить кошелёк и завершить работу
                categories       показать категории
                category add     добавить категорию
                category rename  переименовать категорию
                income           записать доход
                expense          записать расход
                budget           установить лимит расходов по категории
                report           показать отчёт по категориям и датам
                report file      сохранить отчёт в новом текстовом файле
                export           экспортировать кошелёк в новый CSV-файл
                import           заменить кошелёк данными из CSV после подтверждения
                transfer         перевести деньги другому пользователю

                Вводите каждое значение на отдельной строке. Формат даты ГГГГ-ММ-ДД.
                Для операции без указанной даты используется сегодняшний день.
                Для отчёта пустая строка завершает выбор категорий. Пустые даты оставляют период открытым.
                Указывайте суммы через точку или запятую, до двух знаков после разделителя.

                Пример записи дохода в существующую категорию «Зарплата»
                  income
                  Зарплата
                  60000
                  2026-09-01

                Пример отчёта по категории «Еда» за сентябрь
                  report
                  Еда

                  2026-09-01
                  2026-09-30
                """);
    }

    private void credentials(boolean registration) throws IOException, InputStopped {
        String login = read("Логин: ").strip();
        char[] password = readPassword();
        try {
            if (registration) {
                service.register(login, password);
                output.println("Аккаунт создан. Для входа введите login.");
            } else if (service.login(login, password)) {
                output.println("Вход выполнен.");
            } else {
                output.println("Неверный логин или пароль.");
            }
        } finally {
            Arrays.fill(password, '\0');
        }
    }

    private void logout() throws IOException {
        if (service.currentLogin() == null) {
            output.println("Сначала войдите в аккаунт.");
            return;
        }
        service.logout();
        output.println("Кошелёк сохранён. Вы вышли из аккаунта.");
    }

    private void categories() {
        Set<String> categories = service.wallet().categories();
        if (categories.isEmpty()) {
            output.println("Категорий пока нет. Чтобы добавить категорию, введите category add.");
            return;
        }
        output.println("Категории");
        categories.forEach(category -> output.println("  " + category));
    }

    private void addCategory() throws InputStopped {
        Wallet wallet = service.wallet();
        wallet.addCategory(read("Имя категории: "));
        output.println("Категория добавлена.");
    }

    private void renameCategory() throws InputStopped {
        Wallet wallet = service.wallet();
        String oldName = read("Текущее имя категории: ");
        String newName = read("Новое имя категории: ");
        wallet.renameCategory(oldName, newName);
        output.println("Категория переименована.");
        warnBudget(newName.strip(), wallet.budgetStatuses().get(newName.strip()));
    }

    private void record(TransactionType type) throws InputStopped {
        Wallet wallet = service.wallet();
        String category = read("Категория: ");
        String amount = read("Сумма: ");
        String date = read("Дата операции (пустая строка — сегодня): ");
        Transaction transaction = new Transaction(parseDate(date, LocalDate.now()), type, category,
                parseAmount(amount));
        wallet.addTransaction(transaction);
        if (type == TransactionType.INCOME) {
            output.println("Доход записан.");
        } else {
            output.println("Расход записан.");
            warnBudget(transaction.category(), wallet.budgetStatuses().get(transaction.category()));
        }
        warnBalance(wallet);
    }

    private void budget() throws InputStopped {
        Wallet wallet = service.wallet();
        String category = read("Категория: ").strip();
        String limit = read("Лимит расходов: ");
        wallet.setBudget(category, parseAmount(limit));
        output.println("Лимит установлен.");
        warnBudget(category, wallet.budgetStatuses().get(category));
    }

    private void report(boolean saveToFile) throws IOException, InputStopped {
        Wallet wallet = service.wallet();
        output.println("Вводите по одной категории. Пустая строка завершает список, пустой список выбирает все категории.");
        Set<String> categories = new LinkedHashSet<>();
        while (true) {
            String category = read("Категория: ").strip();
            if (category.isEmpty()) {
                break;
            }
            categories.add(category);
        }
        String from = read("Начальная дата (пустая строка — без границы): ");
        String to = read("Конечная дата (пустая строка — без границы): ");
        String fileName = saveToFile ? read("Путь для отчёта: ") : null;
        Report report = wallet.report(categories, parseDate(from, null), parseDate(to, null));
        Map<String, BudgetStatus> budgets = wallet.budgetStatuses();
        if (saveToFile) {
            Path file = outputFile(fileName);
            var text = new StringWriter();
            var writer = new PrintWriter(text);
            printReport(writer, report, budgets);
            Files.writeString(file, text.toString(), CREATE_NEW, WRITE);
            output.println("Отчёт сохранён.");
        } else {
            printReport(output, report, budgets);
        }
    }

    private static void printReport(PrintWriter writer, Report report, Map<String, BudgetStatus> budgets) {
        writer.println("Финансовый отчёт");
        writer.println("Общий доход: " + report.income().toPlainString());
        writer.println("Общие расходы: " + report.expenses().toPlainString());
        writer.println("Баланс отчёта: " + report.income().subtract(report.expenses()).toPlainString());
        if (report.transactionCount() == 0) {
            writer.println("Нет операций по выбранным условиям.");
        }
        if (!report.byCategory().isEmpty()) {
            writer.println();
            writer.println("Итоги по категориям");
            var rows = report.byCategory().entrySet().stream().map(entry -> List.of(entry.getKey(),
                    entry.getValue().income().toPlainString(), entry.getValue().expenses().toPlainString())).toList();
            printTable(writer, List.of("Категория", "Доходы", "Расходы"), rows);
        }

        writer.println();
        writer.println("Бюджеты за всю историю");
        if (budgets.isEmpty()) {
            writer.println("Бюджеты не установлены.");
        } else {
            var rows = budgets.entrySet().stream().map(entry -> {
                BudgetStatus status = entry.getValue();
                return List.of(entry.getKey(), status.limit().toPlainString(), status.spent().toPlainString(),
                        status.remaining().toPlainString(), budgetLabel(status));
            }).toList();
            printTable(writer, List.of("Категория", "Лимит", "Потрачено", "Осталось", "Статус"), rows);
        }
    }

    private static void printTable(PrintWriter writer, List<String> headings, List<List<String>> rows) {
        int[] widths = headings.stream().mapToInt(String::length).toArray();
        for (List<String> row : rows) {
            for (int column = 0; column < widths.length; column++) {
                widths[column] = Math.max(widths[column], row.get(column).length());
            }
        }

        StringBuilder format = new StringBuilder("%-" + widths[0] + "s");
        StringBuilder divider = new StringBuilder("-".repeat(widths[0]));
        for (int column = 1; column < widths.length; column++) {
            format.append(" | %").append(widths[column]).append('s');
            divider.append("-+-").append("-".repeat(widths[column]));
        }
        format.append("%n");
        writer.printf(format.toString(), headings.toArray());
        writer.println(divider);
        for (List<String> row : rows) {
            writer.printf(format.toString(), row.toArray());
        }
    }

    private void exportWallet() throws IOException, InputStopped {
        Wallet wallet = service.wallet();
        Path file = outputFile(read("Путь для экспорта CSV: "));
        WalletCsv.exportWallet(file, wallet);
        output.println("Кошелёк экспортирован.");
    }

    private void importWallet() throws IOException, InputStopped {
        service.wallet();
        Path file = path(read("Путь к CSV: "));
        Path canonical = canonicalPath(file);
        for (String name : SERVICE_FILES) {
            Path internal = dataDirectory.resolve(name);
            if (file.equals(internal) || canonical.equals(canonicalPath(internal))
                    || (Files.exists(file) && Files.exists(internal) && Files.isSameFile(file, internal))) {
                throw new IllegalArgumentException("Служебные файлы приложения нельзя импортировать.");
            }
        }
        Wallet replacement = WalletCsv.importWallet(canonical);
        String confirmation = read("Заменить текущий кошелёк данными из файла? Введите да: ");
        if (!confirmation.strip().equalsIgnoreCase("да")) {
            output.println("Импорт отменён.");
            return;
        }
        service.replaceWallet(replacement);
        output.println("Кошелёк импортирован.");
        Wallet wallet = service.wallet();
        wallet.budgetStatuses().forEach(this::warnBudget);
        warnBalance(wallet);
    }

    private void transfer() throws IOException, InputStopped {
        Wallet wallet = service.wallet();
        String recipient = read("Логин получателя: ").strip();
        String amount = read("Сумма перевода: ");
        String date = read("Дата перевода (пустая строка — сегодня): ");
        service.transfer(recipient, parseAmount(amount), parseDate(date, LocalDate.now()));
        output.println("Перевод выполнен.");
        String category = FinanceService.TRANSFER_CATEGORY;
        warnBudget(category, wallet.budgetStatuses().get(category));
        warnBalance(wallet);
    }

    private void warnBudget(String category, BudgetStatus status) {
        if (status == null) {
            return;
        }
        if (status.exceeded()) {
            output.printf("Внимание, категория «%s» — превышен бюджет.%n", category);
        } else if (status.nearLimit()) {
            output.printf("Внимание, категория «%s» — достигнуто 80%% лимита бюджета.%n", category);
        }
    }

    private void warnBalance(Wallet wallet) {
        int balanceSign = wallet.balance().signum();
        if (balanceSign < 0) {
            output.println("Внимание, расходы превысили доходы.");
        } else if (balanceSign == 0) {
            output.println("Внимание, нулевой баланс.");
        }
    }

    private static String budgetLabel(BudgetStatus status) {
        if (status.exceeded()) {
            return "Превышен";
        }
        return status.nearLimit() ? "От 80%" : "В пределах лимита";
    }

    private static BigDecimal parseAmount(String value) {
        String amount = value.strip();
        if (AMOUNT.matcher(amount).matches()) {
            BigDecimal parsed = new BigDecimal(amount.replace(',', '.'));
            if (parsed.signum() > 0) {
                return parsed;
            }
        }
        throw new IllegalArgumentException("Введите положительную сумму с точностью до двух знаков после запятой.");
    }

    private static LocalDate parseDate(String value, LocalDate defaultDate) {
        String date = value.strip();
        if (date.isEmpty()) {
            return defaultDate;
        }
        if (!DATE.matcher(date).matches()) {
            throw new IllegalArgumentException("Введите дату в формате ГГГГ-ММ-ДД.");
        }
        try {
            return LocalDate.parse(date);
        } catch (DateTimeParseException failure) {
            throw new IllegalArgumentException("Такой даты не существует.");
        }
    }

    private Path outputFile(String value) throws IOException {
        Path file = path(value);
        Path canonical = canonicalPath(file);
        Path canonicalData = canonicalPath(dataDirectory);
        if (file.startsWith(dataDirectory) || canonical.startsWith(canonicalData)
                || (file.getParent() != null && canonicalPath(file.getParent()).startsWith(canonicalData))) {
            throw new IllegalArgumentException("Укажите путь за пределами каталога данных приложения.");
        }
        if (Files.exists(file, NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("Файл уже существует. Укажите путь к новому файлу.");
        }
        return canonical;
    }

    private static Path path(String value) {
        if (value.isBlank()) {
            throw new IllegalArgumentException("Введите путь к файлу.");
        }
        return Path.of(value.strip()).toAbsolutePath().normalize();
    }

    private static Path canonicalPath(Path path) throws IOException {
        try {
            return path.toRealPath();
        } catch (NoSuchFileException missing) {
            Path parent = path.getParent();
            if (parent == null) {
                throw missing;
            }
            return canonicalPath(parent).resolve(path.getFileName());
        }
    }

    private String read(String prompt) throws InputStopped {
        try {
            String line;
            if (console == null) {
                output.println(prompt);
                output.flush();
                line = input.readLine();
            } else {
                line = console.readLine("%s", prompt);
            }
            if (line == null) {
                throw new InputStopped(null);
            }
            return line;
        } catch (IOException | IOError failure) {
            throw new InputStopped(new IOException("Не удалось прочитать ввод.", failure));
        }
    }

    private char[] readPassword() throws InputStopped {
        if (console == null) {
            return read("Пароль: ").toCharArray();
        }
        try {
            char[] password = console.readPassword("Пароль: ");
            if (password == null) {
                throw new InputStopped(null);
            }
            return password;
        } catch (IOError failure) {
            throw new InputStopped(new IOException("Не удалось прочитать пароль.", failure));
        }
    }

    private static final class InputStopped extends Exception {
        private final IOException failure;

        private InputStopped(IOException failure) {
            this.failure = failure;
        }
    }
}
