package dev.localsearch.index;

import dev.localsearch.document.Document;
import dev.localsearch.text.Tokenizer;
import java.nio.file.Path;
import java.util.*;

/** A snapshot is built by one writer and published to read-only searchers. */
public final class InvertedIndex {
    private final Map<Integer, Document> documents = new TreeMap<>();
    private final NavigableMap<String, Map<Integer, Posting>> terms = new TreeMap<>();
    private final Map<Integer, String> contentFiles = new HashMap<>();
    public final Path directory;
    public String root;
    public long created = System.currentTimeMillis(), updated = created;
    public int nextId = 1;
    public Set<String> stopWords = Set.of();
    public long maxFileSize = 10 * 1024 * 1024;
    public List<String> excludes = List.of();

    public InvertedIndex(Path directory) { this.directory = directory.toAbsolutePath().normalize(); }
    public Map<Integer, Document> documents() { return Collections.unmodifiableMap(documents); }
    public NavigableSet<String> vocabulary() { return Collections.unmodifiableNavigableSet(terms.navigableKeySet()); }
    public Map<Integer, Posting> postings(String term) { return Collections.unmodifiableMap(terms.getOrDefault(term, Map.of())); }
    public String contentFile(int id) { return contentFiles.get(id); }
    public void register(Document doc, String contentFile) { documents.put(doc.id(), doc); contentFiles.put(doc.id(), contentFile); }
    public void putPosting(String term, Posting posting) {
        terms.computeIfAbsent(term, t -> new TreeMap<>()).put(posting.documentId(), posting);
    }
    public void add(Document doc, String contentFile, List<Tokenizer.Token> tokens) {
        register(doc, contentFile);
        Map<String, List<Integer>> positions = new HashMap<>();
        for (var token : tokens) if (!stopWords.contains(token.term()))
            positions.computeIfAbsent(token.term(), t -> new ArrayList<>()).add(token.position());
        positions.forEach((term, list) -> putPosting(term, new Posting(doc.id(), list.stream().mapToInt(Integer::intValue).toArray())));
    }
    public void remove(int id) {
        documents.remove(id); contentFiles.remove(id);
        terms.values().forEach(postings -> postings.remove(id));
        terms.values().removeIf(Map::isEmpty);
    }
    public long totalTokens() { return documents.values().stream().mapToLong(Document::tokenCount).sum(); }
    public double averageLength() { return documents.isEmpty() ? 0 : (double) totalTokens() / documents.size(); }
}
