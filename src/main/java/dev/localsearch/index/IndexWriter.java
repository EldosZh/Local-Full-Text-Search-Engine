package dev.localsearch.index;

import dev.localsearch.document.*;
import dev.localsearch.storage.IndexStorage;
import dev.localsearch.text.Tokenizer;
import java.io.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.*;

public final class IndexWriter {
    public record Options(long maxFileSize, List<String> excludes, Set<String> stopWords, int threads) {
        public Options {
            if (maxFileSize <= 0 || threads < 1 || threads > 128) throw new IllegalArgumentException("Positive max size and 1..128 threads required");
            excludes = List.copyOf(excludes); stopWords = Set.copyOf(stopWords);
        }
        public static Options defaults() { return new Options(10 * 1024 * 1024, List.of(), Set.of(), Math.min(8, Runtime.getRuntime().availableProcessors())); }
    }
    public record Report(int discovered, int added, int modified, int unchanged, int deleted, int skipped,
                         int documents, int uniqueTerms, long indexBytes, double durationMs, List<String> warnings) {}
    private record Processed(Path path, Document document, String contentFile, List<Tokenizer.Token> tokens, boolean unchanged, String warning) {}
    public Report index(Path rootPath, Path directory, Options options) throws IOException {
        long start = System.nanoTime();
        Path root = rootPath.toAbsolutePath().normalize(); directory = directory.toAbsolutePath().normalize();
        if (root.startsWith(directory)) throw new IOException("Source root cannot be inside the generated index directory");
        Files.createDirectories(directory);
        try (var channel = FileChannel.open(directory.resolve("write.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
            FileLock lock;
            try { lock = channel.tryLock(); } catch (OverlappingFileLockException e) { throw new IOException("Another index writer is active", e); }
            if (lock == null) throw new IOException("Another index writer is active");
            try (lock) { return update(root, directory, options, start); }
        }
    }
    private Report update(Path root, Path directory, Options options, long start) throws IOException {
        IndexStorage storage = new IndexStorage();
        InvertedIndex index = storage.exists(directory) ? storage.load(directory) : new InvertedIndex(directory);
        if (index.root != null && !index.root.equals(root.toString())) throw new IOException("This index belongs to a different root; choose another --index-dir");
        if (!index.documents().isEmpty() && !index.stopWords.equals(options.stopWords())) throw new IOException("Stop words cannot change on an existing index; use a new --index-dir");
        index.root = root.toString(); index.maxFileSize = options.maxFileSize(); index.excludes = options.excludes(); index.stopWords = options.stopWords();
        var scan = new DocumentScanner().scan(root, directory, options.excludes(), options.maxFileSize());
        List<String> warnings = new ArrayList<>(scan.warnings());
        Map<String, Document> previous = new HashMap<>(); index.documents().values().forEach(doc -> previous.put(doc.path(), doc));
        Files.createDirectories(directory.resolve("content"));
        int added = 0, modified = 0, unchanged = 0, skipped = scan.skipped();
        Set<String> seen = new HashSet<>();
        // Bounded batches cap both queued work and extracted text awaiting a merge.
        try (ExecutorService pool = Executors.newFixedThreadPool(options.threads())) {
            for (int from = 0; from < scan.files().size(); from += options.threads()) {
                List<Future<Processed>> batch = new ArrayList<>();
                for (Path path : scan.files().subList(from, Math.min(from + options.threads(), scan.files().size()))) {
                    seen.add(path.toString()); Document old = previous.get(path.toString());
                    int id = old == null ? index.nextId++ : old.id();
                    batch.add(pool.submit(() -> process(path, old, id, index.directory, options.maxFileSize())));
                }
                for (Future<Processed> future : batch) {
                    Processed result;
                    try { result = future.get(); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException("Indexing interrupted", e); }
                    catch (ExecutionException e) { throw new IOException("Document worker failed", e.getCause()); }
                    if (result.warning() != null) { skipped++; warnings.add(result.warning()); continue; }
                    if (result.unchanged()) {
                        unchanged++;
                        index.register(result.document(), index.contentFile(result.document().id()));
                        continue;
                    }
                    if (previous.containsKey(result.path().toString())) { modified++; index.remove(result.document().id()); } else added++;
                    index.add(result.document(), result.contentFile(), result.tokens());
                }
            }
        }
        int deleted = 0;
        for (Document old : previous.values()) {
            Path path = Path.of(old.path());
            boolean inaccessible = scan.protectedPaths().stream().anyMatch(path::startsWith);
            if (!seen.contains(old.path()) && !inaccessible) { index.remove(old.id()); deleted++; }
        }
        index.updated = System.currentTimeMillis(); storage.save(index);
        return new Report(scan.files().size(), added, modified, unchanged, deleted, skipped, index.documents().size(), index.vocabulary().size(), diskSize(directory), (System.nanoTime() - start) / 1e6, List.copyOf(warnings));
    }
    private Processed process(Path path, Document old, int id, Path directory, long maxSize) {
        try {
            BasicFileAttributes before = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!before.isRegularFile() || before.size() > maxSize) throw new IOException("Not a regular file or exceeds size limit");
            if (old != null && old.size() == before.size() && old.modifiedTime() == before.lastModifiedTime().toMillis())
                return new Processed(path, old, null, null, true, null);
            String hash = TextExtractor.hash(path);
            if (old != null && old.contentHash().equals(hash)) {
                Document refreshed = new Document(id, old.path(), old.fileName(), old.extension(), before.size(), before.lastModifiedTime().toMillis(), hash, old.tokenCount());
                return new Processed(path, refreshed, null, null, true, null);
            }
            String text = new TextExtractor().extract(path);
            BasicFileAttributes after = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!after.isRegularFile() || before.size() != after.size() || !before.lastModifiedTime().equals(after.lastModifiedTime()) || !hash.equals(TextExtractor.hash(path)))
                throw new IOException("File changed during extraction; retry indexing");
            Tokenizer tokenizer = new Tokenizer();
            String contentFile = UUID.randomUUID() + ".txt";
            Path stored = directory.resolve("content").resolve(contentFile);
            Files.writeString(stored, text, StandardOpenOption.CREATE_NEW); IndexStorage.force(stored);
            Document doc = new Document(id, path.toString(), path.getFileName().toString(), DocumentScanner.extension(path), after.size(), after.lastModifiedTime().toMillis(), hash, tokenizer.length(text));
            return new Processed(path, doc, contentFile, tokenizer.tokenize(text), false, null);
        } catch (IOException | RuntimeException e) {
            return new Processed(path, null, null, null, false, "Skipped " + path + ": " + e.getMessage());
        }
    }
    public static long diskSize(Path directory) throws IOException {
        try (var paths = Files.walk(directory)) {
            return paths.filter(Files::isRegularFile).mapToLong(p -> { try { return Files.size(p); } catch (IOException e) { return 0; } }).sum();
        }
    }
}
