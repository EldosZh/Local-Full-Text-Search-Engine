package dev.localsearch;

import dev.localsearch.index.*;
import dev.localsearch.search.*;
import dev.localsearch.storage.IndexStorage;
import dev.localsearch.benchmark.SearchBenchmark;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class EngineTest {
    @TempDir Path temp;
    Path root() throws Exception { return Files.createDirectories(temp.resolve("docs")); }
    Path dir() { return temp.resolve("index"); }
    void put(String name, String text) throws Exception { Files.writeString(root().resolve(name), text); }
    IndexWriter.Report build() throws Exception { return new IndexWriter().index(root(), dir(), IndexWriter.Options.defaults()); }
    InvertedIndex load() throws Exception { return new IndexStorage().load(dir()); }
    SearchEngine.Response search(String query) throws Exception { return new SearchEngine(load()).search(query, SearchEngine.Options.defaults()); }
    @Test void postingsAndPersistence() throws Exception {
        put("a.txt", "java search engine java indexing"); put("b.txt", "java programming"); put("empty.txt", "");
        build(); var index = load();
        assertEquals(3, index.documents().size()); assertEquals(7, index.totalTokens());
        assertEquals(2, index.postings("java").size());
        int id = index.documents().values().stream().filter(d -> d.fileName().equals("a.txt")).findFirst().orElseThrow().id();
        assertArrayEquals(new int[]{0, 3}, index.postings("java").get(id).positions());
        assertEquals(2, index.postings("java").get(id).frequency()); assertTrue(index.postings("missing").isEmpty());
        assertEquals("a.txt", search("search engine").results().getFirst().fileName());
        assertEquals(1, search("search engine").totalHits());
        var java = search("java");
        assertEquals(2, java.totalHits());
        assertEquals(new BM25Scorer().score(2, 2, 3, 5, 7.0 / 3), java.results().stream().filter(r -> r.fileName().equals("a.txt")).findFirst().orElseThrow().score(), 1e-12);
        Files.delete(root().resolve("a.txt"));
        assertTrue(search("indexing").results().getFirst().snippet().contains("indexing"));
    }
    @Test void phraseBooleanPrefixAndFilters() throws Exception {
        put("a.md", "distributed systems java backend"); put("b.txt", "systems for distributed computing python backend mysql");
        put("c.java", "java networking getUserById"); build();
        assertEquals(1, search("\"distributed systems\"").totalHits());
        assertEquals(2, search("distrib*").totalHits());
        assertEquals(2, search("java AND (backend OR networking)").totalHits());
        assertEquals(1, search("backend NOT mysql").totalHits());
        assertEquals(3, search("python OR java").totalHits());
        assertEquals(2, search("NOT mysql").totalHits());
        assertEquals(1, search("user").totalHits());
        assertEquals(1, search("\"getUserById\"").totalHits());
        assertEquals(0, search("\"get user\"").totalHits());
        assertEquals(1, new SearchEngine(load()).search("java", new SearchEngine.Options(10, "java", "c.java", 0, true)).totalHits());
        assertEquals(0, new SearchEngine(load()).search("java", new SearchEngine.Options(10, "md", "", 1e9, false)).totalHits());
    }
    @Test void incrementalLifecycle() throws Exception {
        put("a.txt", "original database");
        assertEquals(1, build().added()); int id = load().documents().keySet().iterator().next();
        assertEquals(1, build().unchanged());
        put("b.txt", "new searchable"); assertEquals(1, build().added());
        put("a.txt", "modified networking content"); assertEquals(1, build().modified());
        assertEquals(id, load().documents().values().stream().filter(d -> d.fileName().equals("a.txt")).findFirst().orElseThrow().id());
        assertEquals(0, search("original").totalHits()); assertEquals(1, search("networking").totalHits());
        Files.delete(root().resolve("a.txt")); assertEquals(1, build().deleted()); assertEquals(0, search("networking").totalHits());
        assertEquals(1, load().documents().size());
    }
    @Test void hashUnchangedAndExclusions() throws Exception {
        put("a.txt", "same content"); Files.createDirectories(root().resolve("node_modules"));
        Files.writeString(root().resolve("node_modules/x.txt"), "ignored");
        put("huge.txt", "x".repeat(100)); put("skip.txt", "excluded");
        var options = new IndexWriter.Options(50, List.of("skip*"), Set.of(), 2);
        var report = new IndexWriter().index(root(), dir(), options);
        assertEquals(1, report.added()); assertEquals(2, report.skipped());
        Files.setLastModifiedTime(root().resolve("a.txt"), java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() + 10000));
        assertEquals(1, new IndexWriter().index(root(), dir(), options).unchanged());
    }
    @Test void stopWordsPreservePositionGaps() throws Exception {
        put("a.txt", "to be or not to be");
        new IndexWriter().index(root(), dir(), new IndexWriter.Options(1000, List.of(), Set.of("or"), 2));
        assertEquals(0, search("or").totalHits()); assertEquals(1, search("\"to be\"").totalHits());
        assertEquals(0, search("\"be not\"").totalHits());
    }
    @Test void concurrentSyntheticCorpusAndBenchmark() throws Exception {
        for (int i = 0; i < 250; i++) put("doc" + i + ".txt", "common java java topic" + i);
        build(); var index = load(); assertEquals(250, index.documents().size()); assertEquals(250, index.postings("common").size());
        assertTrue(index.postings("java").values().stream().allMatch(p -> p.frequency() == 2));
        var report = new SearchBenchmark().run(new SearchEngine(index), List.of("java", "topic12", "comm*"), 20, 2);
        assertEquals(20, report.queries()); assertTrue(report.averageMs() >= 0); assertTrue(report.p99Ms() >= report.medianMs());
    }
    @Test void corruptionAndVersionErrorsAreReadable() throws Exception {
        assertThrows(IndexStorage.MissingIndex.class, this::load);
        put("a.txt", "hello"); build();
        Path snapshot = dir().resolve(Files.readString(dir().resolve("CURRENT")));
        byte[] data = Files.readAllBytes(snapshot); data[7] = 99; Files.write(snapshot, data);
        assertTrue(assertThrows(java.io.IOException.class, this::load).getMessage().contains("Unsupported index version"));
        Files.write(snapshot, new byte[]{1, 2});
        assertTrue(assertThrows(java.io.IOException.class, this::load).getMessage().contains("truncated"));
        Files.writeString(dir().resolve("CURRENT"), "../escape");
        assertTrue(assertThrows(java.io.IOException.class, this::load).getMessage().contains("Invalid snapshot pointer"));
    }
    @Test void failedUpdateDoesNotDestroySnapshot() throws Exception {
        put("a.txt", "searchable"); build(); String pointer = Files.readString(dir().resolve("CURRENT"));
        assertThrows(java.io.IOException.class, () -> new IndexWriter().index(temp.resolve("absent"), dir(), IndexWriter.Options.defaults()));
        assertEquals(pointer, Files.readString(dir().resolve("CURRENT"))); assertEquals(1, search("searchable").totalHits());
    }
    @Test void checksumAndWriterLock() throws Exception {
        put("a.txt", "java database"); build();
        try (var channel = java.nio.channels.FileChannel.open(dir().resolve("write.lock"), StandardOpenOption.WRITE);
             var lock = channel.lock()) {
            assertTrue(assertThrows(java.io.IOException.class, this::build).getMessage().contains("writer"));
            assertEquals(1, search("java").totalHits());
        }
        Path snapshot = dir().resolve(Files.readString(dir().resolve("CURRENT")));
        byte[] data = Files.readAllBytes(snapshot); data[data.length - 1] ^= 1; Files.write(snapshot, data);
        assertTrue(assertThrows(java.io.IOException.class, this::load).getMessage().contains("checksum"));
    }
    @Test void relevantSnippetAndHighlightOffsets() {
        String text = "unrelated ".repeat(80) + "database indexing improves lookup performance";
        var snippet = new SnippetGenerator().generate(text, Set.of("database"));
        assertTrue(snippet.text().startsWith("...")); assertTrue(snippet.text().contains("database"));
        var span = snippet.highlights().getFirst(); assertEquals("database", snippet.text().substring(span.start(), span.end()));
    }
}
