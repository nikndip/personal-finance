package ru.finance.core;

import java.io.IOException;

public interface AccountRepository {
    /** Регистрирует логин и отклоняет дубликат с IllegalArgumentException. */
    void register(String login, char[] password) throws IOException;

    boolean authenticate(String login, char[] password) throws IOException;

    boolean exists(String login) throws IOException;
}
