package ru.finance.core;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

public final class FinanceService implements AutoCloseable {
    public static final String TRANSFER_CATEGORY = "Переводы";

    private static final Pattern LOGIN = Pattern.compile("[A-Za-z0-9_-]{1,32}");

    private final AccountRepository accounts;
    private final WalletRepository repository;

    private String currentLogin;
    private Map<String, Wallet> wallets = Map.of();

    public FinanceService(AccountRepository accounts, WalletRepository repository) {
        this.accounts = Objects.requireNonNull(accounts);
        this.repository = Objects.requireNonNull(repository);
    }

    public void register(String login, char[] password) throws IOException {
        validateCredentials(login, password);
        accounts.register(login, password);
    }

    public boolean login(String login, char[] password) throws IOException {
        validateCredentials(login, password);
        if (!accounts.authenticate(login, password)) {
            return false;
        }

        if (currentLogin != null) {
            repository.save(copyWallets(wallets));
        }
        Map<String, Wallet> loadedWallets = copyWallets(repository.load());
        loadedWallets.computeIfAbsent(login, ignored -> new Wallet());

        wallets = loadedWallets;
        currentLogin = login;
        return true;
    }

    public String currentLogin() {
        return currentLogin;
    }

    public Wallet wallet() {
        requireSession();
        return wallets.get(currentLogin);
    }

    public void replaceWallet(Wallet replacement) {
        requireSession();
        if (replacement == null) {
            throw new IllegalArgumentException("Укажите кошелёк для импорта.");
        }
        wallets.put(currentLogin, replacement.copy());
    }

    public void transfer(String recipient, BigDecimal amount, LocalDate date) throws IOException {
        Wallet sender = wallet();
        validateLogin(recipient);
        if (currentLogin.equals(recipient)) {
            throw new IllegalArgumentException("Нельзя перевести деньги самому себе.");
        }

        Transaction debit = new Transaction(date, TransactionType.EXPENSE, TRANSFER_CATEGORY, amount);
        Transaction credit = new Transaction(date, TransactionType.INCOME, TRANSFER_CATEGORY, debit.amount());
        if (!accounts.exists(recipient)) {
            throw new IllegalArgumentException("Получатель не найден.");
        }
        if (sender.balance().compareTo(debit.amount()) < 0) {
            throw new IllegalArgumentException("Недостаточно средств для перевода.");
        }

        Wallet destination = wallets.computeIfAbsent(recipient, ignored -> new Wallet());
        sender.recordTransfer(debit);
        destination.recordTransfer(credit);
    }

    public void logout() throws IOException {
        if (currentLogin == null) {
            return;
        }
        repository.save(copyWallets(wallets));
        wallets = Map.of();
        currentLogin = null;
    }

    @Override
    public void close() throws IOException {
        logout();
    }

    private void requireSession() {
        if (currentLogin == null) {
            throw new IllegalStateException("Сначала войдите в аккаунт.");
        }
    }

    private static Map<String, Wallet> copyWallets(Map<String, Wallet> source) {
        Map<String, Wallet> copy = new LinkedHashMap<>();
        source.forEach((login, wallet) -> copy.put(login, wallet.copy()));
        return copy;
    }

    private static void validateCredentials(String login, char[] password) {
        validateLogin(login);
        if (password == null || password.length == 0) {
            throw new IllegalArgumentException("Пароль не может быть пустым.");
        }
    }

    private static void validateLogin(String login) {
        if (login == null || !LOGIN.matcher(login).matches()) {
            throw new IllegalArgumentException(
                    "Логин должен содержать от 1 до 32 латинских букв, цифр, дефисов или подчёркиваний.");
        }
    }
}
