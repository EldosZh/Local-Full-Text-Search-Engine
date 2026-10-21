package dev.localsearch.util;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Read only when explicitly present; CLI flags override these defaults. */
public final class Configuration {
    private final Properties properties = new Properties();
    public Configuration(Path path) throws IOException {
        if (Files.exists(path)) try (var input = Files.newInputStream(path)) { properties.load(input); }
    }
    public String get(String key, String fallback) { return properties.getProperty(key, fallback); }
    public static long fileSize(String input) {
        String value = input.strip().toUpperCase(Locale.ROOT);
        long factor = 1;
        for (String suffix : List.of("GB", "MB", "KB", "B")) if (value.endsWith(suffix)) {
            factor = switch (suffix) { case "GB" -> 1024L * 1024 * 1024; case "MB" -> 1024L * 1024; case "KB" -> 1024L; default -> 1; };
            value = value.substring(0, value.length() - suffix.length()).strip(); break;
        }
        long size = Math.multiplyExact(Long.parseLong(value), factor);
        if (size < 1) throw new IllegalArgumentException("File size must be positive");
        return size;
    }
}
