package dev.localsearch.document;

public record Document(int id, String path, String fileName, String extension,
                       long size, long modifiedTime, String contentHash, int tokenCount) {}
