package dev.localsearch.document;

import java.io.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.security.*;
import java.util.HexFormat;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;

public final class TextExtractor {
    public String extract(Path file) throws IOException {
        if (!DocumentScanner.extension(file).equals("pdf")) {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            if (text.indexOf('\0') >= 0) throw new IOException("Binary content in text file");
            return text;
        }
        try (var pdf = Loader.loadPDF(file.toFile())) {
            String text = new PDFTextStripper().getText(pdf);
            if (text.isBlank()) throw new IOException("PDF has no searchable text; OCR is not supported");
            if (text.length() > 20_000_000) throw new IOException("Extracted PDF text exceeds 20 million characters");
            return text;
        }
    }
    public static String hash(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(file)) {
                byte[] buffer = new byte[65536];
                int count;
                while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
