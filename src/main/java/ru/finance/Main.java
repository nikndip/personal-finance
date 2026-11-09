package ru.finance;

import ru.finance.cli.FinanceCli;
import ru.finance.core.FinanceService;
import ru.finance.infra.FileAccountRepository;
import ru.finance.infra.FileWalletRepository;

import java.io.BufferedReader;
import java.io.Console;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
import static java.nio.file.StandardOpenOption.CREATE;
import static java.nio.file.StandardOpenOption.WRITE;

public final class Main {
    private Main() {
    }

    public static void main(String[] args) {
        Console console = System.console();
        var charset = console == null ? StandardCharsets.UTF_8 : console.charset();
        PrintWriter error = new PrintWriter(System.err, true, charset);
        try {
            Path dataDirectory = dataDirectory(args);
            Files.createDirectories(dataDirectory);
            try (FileChannel channel = FileChannel.open(dataDirectory.resolve("finance.lock"),
                    CREATE, WRITE, NOFOLLOW_LINKS);
                 FileLock lock = channel.tryLock()) {
                if (lock == null) {
                    throw new IOException("Каталог данных уже открыт в другом экземпляре приложения.");
                }
                run(dataDirectory, console);
            }
        } catch (IOException | IllegalArgumentException failure) {
            error.println("Ошибка: " + failure.getMessage());
            for (Throwable suppressed : failure.getSuppressed()) {
                error.println("Ошибка: " + suppressed.getMessage());
            }
            System.exit(1);
        }
    }

    private static void run(Path dataDirectory, Console console) throws IOException {
        var service = new FinanceService(new FileAccountRepository(dataDirectory.resolve("accounts.csv")),
                new FileWalletRepository(dataDirectory.resolve("wallets.csv")));
        if (console != null) {
            new FinanceCli(service, console, dataDirectory).run();
        } else {
            var input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
            var output = new PrintWriter(System.out, true, StandardCharsets.UTF_8);
            new FinanceCli(service, input, output, dataDirectory).run();
        }
    }

    private static Path dataDirectory(String[] args) {
        if (args.length == 0) {
            return Path.of("data").toAbsolutePath().normalize();
        }
        if (args.length == 2 && args[0].equals("--data-dir") && !args[1].isBlank()) {
            return Path.of(args[1]).toAbsolutePath().normalize();
        }
        throw new IllegalArgumentException("Запуск: java -jar target/personal-finance.jar [--data-dir <путь>]");
    }
}
