package dev.localsearch.index;

import java.util.Arrays;

public final class Posting {
    private final int documentId;
    private final int[] positions;
    public Posting(int documentId, int[] positions) {
        this.documentId = documentId;
        this.positions = positions.clone();
    }
    public int documentId() { return documentId; }
    public int frequency() { return positions.length; }
    public int[] positions() { return positions.clone(); }
    public boolean contains(int position) { return Arrays.binarySearch(positions, position) >= 0; }
}
