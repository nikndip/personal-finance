package ru.finance.infra;

import ru.finance.core.Wallet;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

public final class WalletCsv {
    private WalletCsv() {
    }

    public static void exportWallet(Path file, Wallet wallet) throws IOException {
        try (var writer = Files.newBufferedWriter(file, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            CsvFile.writeRow(writer, WalletRows.HEADER);
            WalletRows.write(writer, wallet, null);
        } catch (FileAlreadyExistsException failure) {
            throw new IOException("Файл экспорта уже существует: " + file, failure);
        } catch (IOException failure) {
            throw new IOException("Не удалось экспортировать кошелёк. " + failure.getMessage(), failure);
        }
    }

    public static Wallet importWallet(Path file) throws IOException {
        try {
            return WalletRows.read(CsvFile.read(file, WalletRows.HEADER));
        } catch (IOException failure) {
            throw new IOException("Не удалось импортировать кошелёк. " + failure.getMessage(), failure);
        }
    }
}
