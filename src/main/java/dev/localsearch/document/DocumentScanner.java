package dev.localsearch.document;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;

public final class DocumentScanner {
    public static final Set<String> EXTENSIONS = Set.of("txt", "md", "java", "py", "js", "ts", "json", "xml", "yaml", "yml", "properties", "csv", "pdf");
    private static final Set<String> IGNORED = Set.of(".git", ".idea", ".vscode", "node_modules", "target", "build", "dist", ".venv", "venv", "__pycache__", ".search-index", ".tools");
    public record Scan(List<Path> files, Set<Path> protectedPaths, List<String> warnings, int skipped) {}
    public Scan scan(Path root, Path indexDirectory, List<String> excludes, long maxSize) throws IOException {
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Root must be a readable directory, not a symbolic link");
        List<Path> files = new ArrayList<>();
        Set<Path> protectedPaths = new HashSet<>();
        List<String> warnings = new ArrayList<>();
        List<PathMatcher> patterns = excludes.stream().map(s -> root.getFileSystem().getPathMatcher("glob:" + s)).toList();
        int[] skipped = {0};
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            private boolean excluded(Path path) {
                return patterns.stream().anyMatch(p -> p.matches(root.relativize(path)) || p.matches(path.getFileName()));
            }
            @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (dir.equals(indexDirectory) || (!dir.equals(root) && (IGNORED.contains(dir.getFileName().toString()) || excluded(dir))))
                    return FileVisitResult.SKIP_SUBTREE;
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (!attrs.isRegularFile() || excluded(file) || !EXTENSIONS.contains(extension(file))) { skipped[0]++; return FileVisitResult.CONTINUE; }
                if (attrs.size() > maxSize) { skipped[0]++; warnings.add("Too large: " + file); }
                else files.add(file);
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFileFailed(Path file, IOException error) {
                skipped[0]++; protectedPaths.add(file); warnings.add("Cannot access: " + file + " (" + error.getClass().getSimpleName() + ")");
                return FileVisitResult.CONTINUE;
            }
        });
        files.sort(Comparator.naturalOrder());
        return new Scan(List.copyOf(files), Set.copyOf(protectedPaths), List.copyOf(warnings), skipped[0]);
    }
    public static String extension(Path path) {
        String name = path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
