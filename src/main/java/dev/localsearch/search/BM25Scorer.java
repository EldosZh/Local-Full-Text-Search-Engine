package dev.localsearch.search;

public record BM25Scorer(double k1, double b) {
    public BM25Scorer {
        if (!Double.isFinite(k1) || k1 <= 0 || !Double.isFinite(b) || b < 0 || b > 1)
            throw new IllegalArgumentException("BM25 requires k1 > 0 and 0 <= b <= 1");
    }
    public BM25Scorer() { this(1.2, 0.75); }
    public double score(int frequency, int df, int documents, int length, double averageLength) {
        if (frequency == 0 || averageLength == 0) return 0;
        double idf = Math.log(1 + (documents - df + 0.5) / (df + 0.5));
        return idf * frequency * (k1 + 1) / (frequency + k1 * (1 - b + b * length / averageLength));
    }
}
