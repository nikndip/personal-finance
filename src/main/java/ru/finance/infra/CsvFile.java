package ru.finance.infra;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

final class CsvFile {
    private CsvFile() {
    }

    static List<Row> read(Path path, List<String> header) throws IOException {
        try (BufferedReader reader = Files.newBufferedReader(path)) {
            reader.mark(1);
            if (reader.read() != '\ufeff') {
                reader.reset();
            }
            if (!header.equals(readRecord(reader, 1))) {
                throw new IOException("Неожиданный заголовок CSV. Ожидается " + String.join(",", header));
            }
            List<Row> rows = new ArrayList<>();
            int number = 2;
            List<String> fields;
            while ((fields = readRecord(reader, number)) != null) {
                Row row = new Row(number, fields);
                if (fields.size() != header.size()) {
                    throw row.error("неверное количество полей");
                }
                rows.add(row);
                number++;
            }
            return rows;
        }
    }

    static void writeRow(Writer writer, List<String> fields) throws IOException {
        for (int index = 0; index < fields.size(); index++) {
            if (index > 0) {
                writer.write(',');
            }
            String field = fields.get(index);
            if (field.indexOf(',') >= 0 || field.indexOf('"') >= 0
                    || field.indexOf('\r') >= 0 || field.indexOf('\n') >= 0) {
                writer.write('"');
                writer.write(field.replace("\"", "\"\""));
                writer.write('"');
            } else {
                writer.write(field);
            }
        }
        writer.write("\r\n");
    }

    private static List<String> readRecord(BufferedReader reader, int number) throws IOException {
        int next = reader.read();
        if (next == -1) {
            return null;
        }
        List<String> fields = new ArrayList<>();
        do {
            StringBuilder field = new StringBuilder();
            if (next == '"') {
                while (true) {
                    next = reader.read();
                    if (next == -1) {
                        throw error(number, "кавычки в поле не закрыты");
                    }
                    if (next == '"') {
                        next = reader.read();
                        if (next != '"') {
                            break;
                        }
                    }
                    field.append((char) next);
                }
                if (!isSeparator(next)) {
                    throw error(number, "после закрывающей кавычки ожидается разделитель");
                }
            } else {
                while (!isSeparator(next)) {
                    if (next == '"') {
                        throw error(number, "кавычка внутри неэкранированного поля");
                    }
                    field.append((char) next);
                    next = reader.read();
                }
            }
            fields.add(field.toString());
            if (next != ',') {
                break;
            }
            next = reader.read();
        } while (true);
        if (next == '\r') {
            reader.mark(1);
            if (reader.read() != '\n') {
                reader.reset();
            }
        }
        return fields;
    }

    private static boolean isSeparator(int value) {
        return value == ',' || value == '\r' || value == '\n' || value == -1;
    }

    private static IOException error(int number, String reason) {
        return new IOException("Запись CSV №" + number + ": " + reason + ".");
    }

    record Row(int number, List<String> fields) {
        String field(int index) {
            return fields.get(index);
        }

        void requireEmpty(int from, int to) throws IOException {
            for (int index = from; index < to; index++) {
                if (!field(index).isEmpty()) {
                    throw error("заполнено поле, которое должно быть пустым");
                }
            }
        }

        IOException error(String reason) {
            return CsvFile.error(number, reason);
        }
    }
}
