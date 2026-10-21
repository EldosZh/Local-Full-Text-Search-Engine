package dev.localsearch.benchmark;

import dev.localsearch.search.SearchEngine;
import java.io.IOException;
import java.util.*;

public final class SearchBenchmark {
    public record Report(int queries, int warmup, double averageMs, double medianMs, double p95Ms, double p99Ms, double minMs, double maxMs) {}
    public Report run(SearchEngine engine, List<String> queries, int iterations, int warmup) throws IOException {
        if (queries.isEmpty() || iterations < 1 || iterations > 1_000_000 || warmup < 0 || warmup > 10000) throw new IllegalArgumentException("Require queries, 1..1000000 iterations and 0..10000 warmup");
        for (int i = 0; i < warmup; i++) engine.search(queries.get(i % queries.size()), SearchEngine.Options.defaults());
        double[] durations = new double[iterations];
        for (int i = 0; i < iterations; i++) {
            long start = System.nanoTime(); engine.search(queries.get(i % queries.size()), SearchEngine.Options.defaults());
            durations[i] = (System.nanoTime() - start) / 1e6;
        }
        Arrays.sort(durations);
        return new Report(iterations, warmup, Arrays.stream(durations).average().orElseThrow(), percentile(durations, .5), percentile(durations, .95), percentile(durations, .99), durations[0], durations[iterations - 1]);
    }
    /** Nearest-rank percentile: sorted[ceil(p*N)-1]. */
    public static double percentile(double[] sorted, double p) {
        if (sorted.length == 0 || p <= 0 || p > 1) throw new IllegalArgumentException("Invalid percentile input");
        return sorted[(int) Math.ceil(p * sorted.length) - 1];
    }
}
