package ru.finance.infra;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

final class AtomicFile {
    private AtomicFile() {
    }

    static void write(Path file, Contents contents) throws IOException {
        Path destination = file.toAbsolutePath().normalize();
        Path parent = destination.getParent();
        if (parent == null) {
            throw new IOException("Укажите путь к файлу.");
        }
        Files.createDirectories(parent);
        Path temporary = Files.createTempFile(parent, ".finance-", ".tmp");
        try {
            try (BufferedWriter writer = Files.newBufferedWriter(temporary)) {
                contents.writeTo(writer);
            }
            Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException | RuntimeException failure) {
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
    }

    @FunctionalInterface
    interface Contents {
        void writeTo(BufferedWriter writer) throws IOException;
    }
}
