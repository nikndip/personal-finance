package ru.finance.infra;

import ru.finance.core.AccountRepository;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

public final class FileAccountRepository implements AccountRepository {
    private static final List<String> HEADER = List.of("login", "salt", "password_hash");
    private static final Pattern LOGIN = Pattern.compile("[A-Za-z0-9_-]{1,32}");
    private static final int SALT_BYTES = 16;
    private static final int HASH_BYTES = 32;
    private static final int ITERATIONS = 600_000;

    private final Path file;
    private final SecureRandom random = new SecureRandom();

    public FileAccountRepository(Path file) {
        this.file = file.toAbsolutePath().normalize();
    }

    @Override
    public synchronized void register(String login, char[] password) throws IOException {
        if (!validLogin(login)) {
            throw new IllegalArgumentException("Логин должен содержать от 1 до 32 латинских букв, цифр, дефисов или подчёркиваний.");
        }
        Map<String, Credentials> accounts = read();
        if (accounts.containsKey(login)) {
            throw new IllegalArgumentException("Такой логин уже зарегистрирован.");
        }
        byte[] salt = new byte[SALT_BYTES];
        random.nextBytes(salt);
        accounts.put(login, new Credentials(salt, hash(password, salt)));
        try {
            AtomicFile.write(file, writer -> {
                CsvFile.writeRow(writer, HEADER);
                Base64.Encoder encoder = Base64.getEncoder();
                for (var entry : accounts.entrySet()) {
                    Credentials credentials = entry.getValue();
                    CsvFile.writeRow(writer, List.of(entry.getKey(), encoder.encodeToString(credentials.salt()),
                            encoder.encodeToString(credentials.hash())));
                }
            });
        } catch (IOException failure) {
            throw new IOException("Не удалось сохранить учётные записи. " + failure.getMessage(), failure);
        }
    }

    @Override
    public synchronized boolean authenticate(String login, char[] password) throws IOException {
        Credentials credentials = read().get(login);
        return credentials != null && MessageDigest.isEqual(credentials.hash(), hash(password, credentials.salt()));
    }

    @Override
    public synchronized boolean exists(String login) throws IOException {
        return read().containsKey(login);
    }

    static boolean validLogin(String login) {
        return login != null && LOGIN.matcher(login).matches();
    }

    private Map<String, Credentials> read() throws IOException {
        Map<String, Credentials> accounts = new LinkedHashMap<>();
        try {
            for (CsvFile.Row row : CsvFile.read(file, HEADER)) {
                String login = row.field(0);
                if (!validLogin(login)) {
                    throw row.error("недопустимый логин");
                }
                Credentials credentials = readCredentials(row);
                if (accounts.putIfAbsent(login, credentials) != null) {
                    throw row.error("логин встречается несколько раз");
                }
            }
        } catch (NoSuchFileException missing) {
            return accounts;
        } catch (IOException failure) {
            throw new IOException("Не удалось прочитать учётные записи. " + failure.getMessage(), failure);
        }
        return accounts;
    }

    private static Credentials readCredentials(CsvFile.Row row) throws IOException {
        Base64.Decoder decoder = Base64.getDecoder();
        byte[] salt;
        byte[] hash;
        try {
            salt = decoder.decode(row.field(1));
            hash = decoder.decode(row.field(2));
        } catch (IllegalArgumentException failure) {
            throw row.error("повреждены данные пароля");
        }
        if (salt.length != SALT_BYTES || hash.length != HASH_BYTES) {
            throw row.error("неверный размер данных пароля");
        }
        return new Credentials(salt, hash);
    }

    private static byte[] hash(char[] password, byte[] salt) throws IOException {
        PBEKeySpec spec = new PBEKeySpec(password, salt, ITERATIONS, HASH_BYTES * Byte.SIZE);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (GeneralSecurityException failure) {
            throw new IOException("Не удалось обработать пароль.", failure);
        } finally {
            spec.clearPassword();
        }
    }

    private record Credentials(byte[] salt, byte[] hash) {
    }
}
