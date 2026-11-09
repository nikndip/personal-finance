package ru.finance.core;

import java.io.IOException;
import java.util.Map;

public interface WalletRepository {
    Map<String, Wallet> load() throws IOException;

    void save(Map<String, Wallet> wallets) throws IOException;
}
