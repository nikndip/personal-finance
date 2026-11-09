package ru.finance.infra;

import ru.finance.core.Wallet;
import ru.finance.core.WalletRepository;

import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class FileWalletRepository implements WalletRepository {
    private static final List<String> HEADER = List.of("login", "kind", "category", "date", "type", "amount");

    private final Path file;

    public FileWalletRepository(Path file) {
        this.file = file.toAbsolutePath().normalize();
    }

    @Override
    public Map<String, Wallet> load() throws IOException {
        Map<String, Wallet> wallets = new LinkedHashMap<>();
        try {
            Map<String, List<CsvFile.Row>> byLogin = new LinkedHashMap<>();
            for (CsvFile.Row row : CsvFile.read(file, HEADER)) {
                String login = row.field(0);
                if (!FileAccountRepository.validLogin(login)) {
                    throw row.error("недопустимый логин владельца");
                }
                if (row.field(1).equals("WALLET")) {
                    row.requireEmpty(2, 6);
                    if (byLogin.putIfAbsent(login, new ArrayList<>()) != null) {
                        throw row.error("кошелёк владельца уже объявлен");
                    }
                } else {
                    List<CsvFile.Row> walletRows = byLogin.get(login);
                    if (walletRows == null) {
                        throw row.error("кошелёк владельца не объявлен");
                    }
                    walletRows.add(new CsvFile.Row(row.number(), row.fields().subList(1, HEADER.size())));
                }
            }
            for (var entry : byLogin.entrySet()) {
                wallets.put(entry.getKey(), WalletRows.read(entry.getValue()));
            }
        } catch (NoSuchFileException missing) {
            return wallets;
        } catch (IOException failure) {
            throw new IOException("Не удалось загрузить кошельки. " + failure.getMessage(), failure);
        }
        return wallets;
    }

    @Override
    public void save(Map<String, Wallet> wallets) throws IOException {
        try {
            AtomicFile.write(file, writer -> {
                CsvFile.writeRow(writer, HEADER);
                for (var entry : wallets.entrySet()) {
                    String login = entry.getKey();
                    if (!FileAccountRepository.validLogin(login)) {
                        throw new IOException("Недопустимый логин владельца кошелька.");
                    }
                    CsvFile.writeRow(writer, List.of(login, "WALLET", "", "", "", ""));
                    WalletRows.write(writer, entry.getValue(), login);
                }
            });
        } catch (IOException failure) {
            throw new IOException("Не удалось сохранить кошельки. " + failure.getMessage(), failure);
        }
    }
}
