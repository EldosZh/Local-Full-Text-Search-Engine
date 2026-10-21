package dev.localsearch.util;

import dev.localsearch.index.*;
import dev.localsearch.document.Document;
import java.io.IOException;
import java.time.Instant;
import java.util.*;

public final class IndexInfo {
    private IndexInfo() {}
    public static Map<String, Object> info(InvertedIndex index, boolean hidePaths) throws IOException {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("root", hidePaths ? "." : index.root); info.put("documents", index.documents().size());
        info.put("uniqueTerms", index.vocabulary().size()); info.put("totalTokens", index.totalTokens());
        info.put("averageLength", index.averageLength()); info.put("indexBytes", IndexWriter.diskSize(index.directory));
        info.put("created", Instant.ofEpochMilli(index.created).toString()); info.put("updated", Instant.ofEpochMilli(index.updated).toString());
        return info;
    }
    public static Map<String, Object> stats(InvertedIndex index, boolean hidePaths) throws IOException {
        Map<String, Object> stats = new LinkedHashMap<>(info(index, hidePaths));
        Map<String, Long> byType = new TreeMap<>();
        index.documents().values().forEach(d -> byType.merge(d.extension(), 1L, Long::sum));
        stats.put("documentsByExtension", byType);
        stats.put("largestDocuments", index.documents().values().stream().sorted(Comparator.comparingLong(Document::size).reversed()).limit(10)
                .map(d -> Map.of("fileName", d.fileName(), "size", d.size())).toList());
        stats.put("topTerms", index.vocabulary().stream().map(t -> {
            var postings = index.postings(t);
            return Map.<String, Object>of("term", t, "documentFrequency", postings.size(), "collectionFrequency", postings.values().stream().mapToLong(Posting::frequency).sum());
        }).sorted(Comparator.<Map<String, Object>>comparingLong(t -> (long) t.get("collectionFrequency")).reversed().thenComparing(t -> (String) t.get("term"))).limit(20).toList());
        return stats;
    }
}
