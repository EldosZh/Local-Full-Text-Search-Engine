package dev.localsearch.search;

import dev.localsearch.document.Document;
import dev.localsearch.index.*;
import dev.localsearch.search.query.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;

public final class SearchEngine {
    public record Options(int top, String type, String path, double minScore, boolean hidePaths) {
        public Options {
            if (top < 1 || top > 1000) throw new IllegalArgumentException("Limit must be between 1 and 1000");
            if (!Double.isFinite(minScore) || minScore < 0) throw new IllegalArgumentException("Minimum score must be finite and nonnegative");
            type = type == null ? "" : type.replaceFirst("^\\.", "").toLowerCase(Locale.ROOT);
            if (!type.isEmpty() && !dev.localsearch.document.DocumentScanner.EXTENSIONS.contains(type)) throw new IllegalArgumentException("Unsupported file type filter");
            path = path == null ? "" : path.replace('\\', '/');
        }
        public static Options defaults() { return new Options(10, "", "", 0, false); }
    }
    public record Result(int rank, String fileName, String path, String type, double score, String snippet, List<SnippetGenerator.Highlight> highlights) {}
    public record Response(String query, int totalHits, double durationMs, List<Result> results) {}
    private record Scored(Document document, double score) {}
    private final InvertedIndex index;
    private final BM25Scorer scorer;
    public SearchEngine(InvertedIndex index) { this(index, new BM25Scorer()); }
    public SearchEngine(InvertedIndex index, BM25Scorer scorer) { this.index = index; this.scorer = scorer; }
    public Response search(String input, Options options) throws IOException {
        long start = System.nanoTime();
        Query query = new QueryParser(input).parse();
        Set<Integer> candidates = evaluate(query);
        Set<String> positive = new TreeSet<>(); collect(query, false, positive);
        Map<Integer, Double> scores = new HashMap<>();
        double average = index.averageLength();
        // Visit positive-term postings once rather than looking up every term for every candidate.
        for (String term : positive) {
            var postings = index.postings(term);
            for (var posting : postings.values()) if (candidates.contains(posting.documentId())) {
                Document doc = index.documents().get(posting.documentId());
                scores.merge(doc.id(), scorer.score(posting.frequency(), postings.size(), index.documents().size(), doc.tokenCount(), average), Double::sum);
            }
        }
        Comparator<Scored> best = Comparator.comparingDouble(Scored::score).reversed().thenComparing(s -> s.document().path());
        PriorityQueue<Scored> heap = new PriorityQueue<>(best.reversed());
        int total = 0;
        for (int id : candidates) {
            Document doc = index.documents().get(id);
            if (!options.type().isEmpty() && !doc.extension().equals(options.type())) continue;
            if (!options.path().isEmpty() && !doc.path().replace('\\', '/').contains(options.path())) continue;
            double score = scores.getOrDefault(id, 0.0);
            if (score < options.minScore()) continue;
            total++; heap.add(new Scored(doc, score)); if (heap.size() > options.top()) heap.poll();
        }
        List<Scored> ranked = new ArrayList<>(heap); ranked.sort(best);
        List<Result> results = new ArrayList<>();
        for (Scored scored : ranked) {
            Document doc = scored.document();
            String text = Files.readString(index.directory.resolve("content").resolve(index.contentFile(doc.id())));
            var snippet = new SnippetGenerator().generate(text, positive);
            String path = options.hidePaths() ? Path.of(index.root).relativize(Path.of(doc.path())).toString() : doc.path();
            results.add(new Result(results.size() + 1, doc.fileName(), path, doc.extension(), scored.score(), snippet.text(), snippet.highlights()));
        }
        return new Response(input, total, (System.nanoTime() - start) / 1e6, List.copyOf(results));
    }
    private Set<Integer> evaluate(Query query) {
        return switch (query) {
            case Query.Term t -> {
                Set<Integer> ids = new HashSet<>(); for (String term : expand(t)) ids.addAll(index.postings(term).keySet()); yield ids;
            }
            case Query.Phrase p -> phrase(p.terms());
            case Query.And a -> { Set<Integer> ids = evaluate(a.left()); ids.retainAll(evaluate(a.right())); yield ids; }
            case Query.Or o -> { Set<Integer> ids = evaluate(o.left()); ids.addAll(evaluate(o.right())); yield ids; }
            case Query.Not n -> { Set<Integer> ids = new HashSet<>(index.documents().keySet()); ids.removeAll(evaluate(n.child())); yield ids; }
        };
    }
    private Set<Integer> phrase(List<String> terms) {
        List<Map<Integer, Posting>> lists = terms.stream().map(index::postings).toList();
        Set<Integer> candidates = new HashSet<>(lists.getFirst().keySet());
        for (var list : lists) candidates.retainAll(list.keySet());
        candidates.removeIf(id -> {
            for (int position : lists.getFirst().get(id).positions()) {
                boolean matches = true;
                for (int i = 1; i < lists.size(); i++) if (!lists.get(i).get(id).contains(position + i)) { matches = false; break; }
                if (matches) return false;
            }
            return true;
        });
        return candidates;
    }
    private List<String> expand(Query.Term term) {
        if (!term.prefix()) return index.stopWords.contains(term.value()) ? List.of() : List.of(term.value());
        List<String> matches = new ArrayList<>();
        for (String value : index.vocabulary().tailSet(term.value(), true)) {
            if (!value.startsWith(term.value())) break;
            matches.add(value);
            if (matches.size() > 10000) throw new IllegalArgumentException("Prefix expands beyond 10000 terms; use a longer prefix");
        }
        return matches;
    }
    private void collect(Query query, boolean negated, Set<String> terms) {
        switch (query) {
            case Query.Term t -> { if (!negated) terms.addAll(expand(t)); }
            case Query.Phrase p -> { if (!negated) terms.addAll(p.terms()); }
            case Query.And a -> { collect(a.left(), negated, terms); collect(a.right(), negated, terms); }
            case Query.Or o -> { collect(o.left(), negated, terms); collect(o.right(), negated, terms); }
            case Query.Not n -> collect(n.child(), !negated, terms);
        }
    }
}
