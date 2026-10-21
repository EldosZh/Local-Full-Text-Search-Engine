package dev.localsearch.cli;

import dev.localsearch.benchmark.SearchBenchmark;
import dev.localsearch.index.*;
import dev.localsearch.search.*;
import dev.localsearch.server.SearchServer;
import dev.localsearch.storage.IndexStorage;
import dev.localsearch.text.Tokenizer;
import dev.localsearch.util.*;
import picocli.CommandLine;
import picocli.CommandLine.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.channels.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CountDownLatch;

@Command(name = "local-search", version = "Local Full-Text Search Engine 1.0.0", mixinStandardHelpOptions = true,
        description = "Search local documents using a persistent positional index and BM25.",
        scope = ScopeType.INHERIT)
public final class SearchCli implements Runnable {
    @Option(names = "--index-dir", description = "Directory containing named indexes (default: .search-index)", scope = ScopeType.INHERIT) Path baseDirectory;
    @Option(names = {"--index", "--name"}, defaultValue = "default", description = "Index name (default: ${DEFAULT-VALUE})", scope = ScopeType.INHERIT) String name;
    @Option(names = "--config", description = "Properties configuration file", scope = ScopeType.INHERIT) Path configFile = Path.of(System.getProperty("user.home"), ".local-search", "config.properties");
    @Option(names = "--debug", description = "Print diagnostic stack traces to stderr", scope = ScopeType.INHERIT) boolean debug;
    @Spec CommandLine.Model.CommandSpec spec;
    public static void main(String[] args) {
        if (Arrays.asList(args).contains("--debug")) System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", "debug");
        System.exit(commandLine().execute(args));
    }
    public static CommandLine commandLine() {
        var cli = new SearchCli();
        return new CommandLine(cli)
                .setOut(new PrintWriter(new OutputStreamWriter(System.out, StandardCharsets.UTF_8), true))
                .setErr(new PrintWriter(new OutputStreamWriter(System.err, StandardCharsets.UTF_8), true))
                .setExecutionExceptionHandler((exception, command, parseResult) -> {
            command.getErr().println("Error: " + exception.getMessage());
            if (cli.debug) exception.printStackTrace(command.getErr());
            if (exception instanceof IndexStorage.MissingIndex) return 3;
            return exception instanceof IllegalArgumentException ? 2 : 1;
        });
    }
    @Override public void run() { spec.commandLine().usage(spec.commandLine().getOut()); }
    private Configuration config() throws IOException { return new Configuration(configFile); }
    private Path directory() throws IOException {
        if (!name.matches("[A-Za-z0-9_-]{1,64}")) throw new IllegalArgumentException("Index name must contain 1..64 letters, numbers, underscores or hyphens");
        Path base = baseDirectory == null ? Path.of(config().get("index.directory", ".search-index")) : baseDirectory;
        return base.toAbsolutePath().normalize().resolve(name);
    }
    private InvertedIndex load() throws IOException { return new IndexStorage().load(directory()); }
    private BM25Scorer scorer() throws IOException {
        return new BM25Scorer(Double.parseDouble(config().get("bm25.k1", "1.2")), Double.parseDouble(config().get("bm25.b", ".75")));
    }
    private void print(Object value) throws IOException { spec.commandLine().getOut().println(Json.encode(value)); }
    private IndexWriter.Options options(String max, List<String> excludes, Path stopFile, Integer threads, InvertedIndex previous) throws IOException {
        var config = config();
        long size = max != null ? Configuration.fileSize(max) : previous != null ? previous.maxFileSize : Configuration.fileSize(config.get("max.file.size", "10MB"));
        List<String> ignored = excludes != null ? excludes : previous != null ? previous.excludes : csv(config.get("exclude", ""));
        Set<String> stop = previous != null ? previous.stopWords : Set.of();
        if (stopFile != null) {
            Set<String> words = new HashSet<>();
            for (String line : Files.readAllLines(stopFile)) if (!line.isBlank()) {
                var tokens = new Tokenizer().words(line.strip());
                if (tokens.size() != 1) throw new IllegalArgumentException("Stop-word file requires one word per line");
                words.add(tokens.getFirst());
            }
            stop = Set.copyOf(words);
        }
        return new IndexWriter.Options(size, ignored, stop, threads != null ? threads : Integer.parseInt(config.get("threads", "8")));
    }
    private static List<String> csv(String value) { return value.isBlank() ? List.of() : Arrays.stream(value.split(",")).map(String::strip).toList(); }
    @Command(name = "index", description = "Discover files and create or incrementally update an index")
    void index(@Parameters(index = "0", description = "Source directory") Path root,
               @Option(names = "--exclude", description = "Exclude directory name or relative glob; repeatable") List<String> excludes,
               @Option(names = "--max-file-size", description = "Maximum bytes, e.g. 20MB") String max,
               @Option(names = "--stop-words", description = "UTF-8 file containing one stop word per line") Path stopFile,
               @Option(names = "--threads", description = "Bounded worker count, 1..128") Integer threads) throws IOException {
        Path dir = directory(); var storage = new IndexStorage();
        InvertedIndex previous = storage.exists(dir) ? storage.load(dir) : null;
        print(new IndexWriter().index(root, dir, options(max, excludes, stopFile, threads, previous)));
    }
    @Command(name = "update", description = "Incrementally update the saved root using saved extraction settings")
    void update(@Option(names = "--threads") Integer threads) throws IOException {
        var previous = load();
        print(new IndexWriter().index(Path.of(previous.root), directory(), options(null, null, null, threads, previous)));
    }
    @Command(name = "search", description = "Search terms, quoted phrases, boolean expressions and trailing prefixes")
    void search(@Parameters(index = "0", description = "Query; quote the whole expression in your shell") String query,
                @Option(names = "--top", description = "Results to return, 1..1000 (default: 10)") Integer top,
                @Option(names = "--type", description = "Extension filter, e.g. java or md") String type,
                @Option(names = "--path", description = "Indexed path substring filter") String path,
                @Option(names = "--min-score", defaultValue = "0") double minScore,
                @Option(names = "--json", description = "Write valid JSON only to stdout") boolean json,
                @Option(names = "--no-color", description = "Disable ANSI highlighting") boolean noColor) throws IOException {
        int limit = top != null ? top : Integer.parseInt(config().get("search.top", "10"));
        var response = new SearchEngine(load(), scorer()).search(query, new SearchEngine.Options(limit, type, path, minScore, false));
        if (json) { print(response); return; }
        var out = spec.commandLine().getOut();
        out.println("Search results for: " + query);
        boolean color = !noColor && System.console() != null && Help.Ansi.AUTO.enabled();
        for (var result : response.results()) {
            out.printf(Locale.ROOT, "%n#%d %s [%s]%nScore: %.6f%n", result.rank(), result.fileName(), result.type(), result.score());
            String snippet = result.snippet();
            if (color) {
                var spans = new ArrayList<>(result.highlights()); spans.sort(Comparator.comparingInt(SnippetGenerator.Highlight::start).reversed());
                for (var span : spans) snippet = snippet.substring(0, span.start()) + "\u001b[1;33m" + snippet.substring(span.start(), span.end()) + "\u001b[0m" + snippet.substring(span.end());
            }
            out.println(snippet); out.println(result.path());
        }
        out.printf(Locale.ROOT, "%nFound %d results in %.3f ms.%n", response.totalHits(), response.durationMs());
    }
    @Command(name = "info", description = "Display measured index metadata")
    void info() throws IOException { print(IndexInfo.info(load(), false)); }
    @Command(name = "stats", description = "Display extensions, largest files and term DF/collection frequency")
    void stats() throws IOException { print(IndexInfo.stats(load(), false)); }
    @Command(name = "list-documents", description = "List indexed document metadata as JSON")
    void documents() throws IOException { print(load().documents().values()); }
    @Command(name = "benchmark", description = "Measure actual search latency, including stored-content snippets")
    void benchmark(@Option(names = "--queries", description = "UTF-8 file, one query per line") Path queries,
                   @Option(names = "--iterations", defaultValue = "100") int iterations,
                   @Option(names = "--warmup", defaultValue = "10") int warmup) throws IOException {
        var index = load();
        List<String> inputs = queries == null ? index.vocabulary().stream().limit(20).toList() : Files.readAllLines(queries).stream().filter(s -> !s.isBlank()).toList();
        print(new SearchBenchmark().run(new SearchEngine(index, scorer()), inputs, iterations, warmup));
    }
    @Command(name = "serve", description = "Run the loopback-only read-only HTTP API; Ctrl+C stops it")
    void serve(@Option(names = "--port", defaultValue = "8080") int port,
               @Option(names = "--hide-paths", description = "Expose relative result paths and hide the source root") boolean hidePaths) throws Exception {
        try (var server = new SearchServer(directory(), port, hidePaths, scorer())) {
            Thread hook = new Thread(server::close); Runtime.getRuntime().addShutdownHook(hook);
            try {
                server.start(); spec.commandLine().getOut().println("Read-only API listening on loopback port " + server.port());
                spec.commandLine().getOut().flush(); new CountDownLatch(1).await();
            } finally { try { Runtime.getRuntime().removeShutdownHook(hook); } catch (IllegalStateException ignored) { /* JVM is already shutting down. */ } }
        }
    }
    @Command(name = "delete-index", description = "Delete generated index data only; source documents are preserved")
    int delete(@Option(names = "--yes", description = "Confirm deletion without prompting") boolean yes) throws IOException {
        Path dir = directory(); load();
        if (!yes) {
            var console = System.console();
            if (console == null) throw new IllegalArgumentException("Interactive confirmation unavailable; pass --yes to confirm index deletion");
            String answer = console.readLine("Delete generated index %s? Source documents are preserved. [y/N] ", name);
            if (!"y".equalsIgnoreCase(answer)) return 0;
        }
        try (var channel = FileChannel.open(dir.resolve("write.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
            FileLock lock;
            try { lock = channel.tryLock(); } catch (OverlappingFileLockException e) { throw new IOException("An index writer is active", e); }
            if (lock == null) throw new IOException("An index writer is active");
            try (lock) {
                // Only known generated names are eligible; never recursively delete an arbitrary directory.
                List<Path> generated;
                try (var files = Files.list(dir)) { generated = files.filter(p -> p.getFileName().toString().matches("snapshot-[a-f0-9-]+\\.bin|CURRENT(?:-[a-f0-9-]+)?")).toList(); }
                Files.deleteIfExists(dir.resolve("CURRENT"));
                for (Path file : generated) Files.deleteIfExists(file);
                Path content = dir.resolve("content");
                if (Files.isDirectory(content, LinkOption.NOFOLLOW_LINKS)) try (var files = Files.list(content)) {
                    for (Path file : files.filter(p -> p.getFileName().toString().matches("[a-f0-9-]+\\.txt")).toList()) Files.delete(file);
                }
            }
        }
        spec.commandLine().getOut().println("Generated index deleted."); return 0;
    }
}
