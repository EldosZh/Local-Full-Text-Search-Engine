import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.font.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Fresh-process executable verification. Run from the repository root after packaging. */
class Verify {
    static final Path JAR = Path.of("target/local-full-text-search-engine.jar").toAbsolutePath();
    static final String JAVA = Path.of(System.getProperty("java.home"), "bin", "java").toString();
    static final ObjectMapper JSON = new ObjectMapper();
    static final List<String> LOG = new ArrayList<>();
    static Path indexes;
    static List<String> command(String... args) {
        List<String> command = new ArrayList<>(List.of(JAVA, "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8", "-jar", JAR.toString(), "--index-dir", indexes.toString()));
        // Windows' native argv parser consumes embedded quotes unless escaped.
        boolean windows = System.getProperty("os.name").startsWith("Windows");
        for (String arg : args) command.add(windows ? arg.replace("\"", "\\\"") : arg);
        return command;
    }
    static String run(String... args) throws Exception {
        Path out = Files.createTempFile("local-search-verify", ".out"), err = Files.createTempFile("local-search-verify", ".err");
        try {
            Process process = new ProcessBuilder(command(args)).redirectOutput(out.toFile()).redirectError(err.toFile()).start();
            if (!process.waitFor(90, TimeUnit.SECONDS)) { process.destroyForcibly(); throw new AssertionError("Command timed out"); }
            String output = Files.readString(out), errors = new String(Files.readAllBytes(err), java.nio.charset.StandardCharsets.UTF_8);
            if (process.exitValue() != 0) throw new AssertionError(Arrays.toString(args) + ": " + errors);
            LOG.add("$ " + String.join(" ", args) + "\n" + output);
            System.out.println("PASS " + Arrays.toString(args)); return output;
        } finally { Files.deleteIfExists(out); Files.deleteIfExists(err); }
    }
    static JsonNode json(String... args) throws Exception { return JSON.readTree(run(args)); }
    static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    static void hits(String query, int expected) throws Exception {
        JsonNode response = json("search", query, "--json");
        require(response.get("totalHits").asInt() == expected, "Unexpected hits: " + response);
    }
    public static void main(String[] args) throws Exception {
        Path workspace = Files.createTempDirectory("local-search-verification-");
        indexes = workspace.resolve("indexes");
        Path docs = Files.createDirectory(workspace.resolve("documents"));
        try {
            try (var files = Files.list(Path.of("examples/documents"))) {
                for (Path file : files.toList()) Files.copy(file, docs.resolve(file.getFileName()));
            }
            run("--help"); run("--version");
            require(json("index", docs.toString()).get("added").asInt() == 7, "Initial count");
            run("info"); run("stats"); run("search", "distributed systems"); run("search", "database index");
            hits("\"distributed systems\"", 1); hits("java AND networking", 1); hits("distrib*", 3);
            require(json("search", "java", "--type", "java", "--json").get("totalHits").asInt() == 1, "Type filter");
            // Every run launches a new JVM; this explicitly verifies restart persistence.
            hits("\"distributed systems\"", 1);
            hits("Қазақстан", 1);
            require(json("update").get("unchanged").asInt() == 7, "Unchanged count");
            Path lifecycle = docs.resolve("lifecycle.txt"); Files.writeString(lifecycle, "uniquelifecycleinitial");
            require(json("update").get("added").asInt() == 1, "New file"); hits("uniquelifecycleinitial", 1);
            Files.writeString(lifecycle, "uniquelifecyclemodified longer");
            require(json("update").get("modified").asInt() == 1, "Modified file"); hits("uniquelifecycleinitial", 0); hits("uniquelifecyclemodified", 1);
            Files.delete(lifecycle); require(json("update").get("deleted").asInt() == 1, "Deleted file"); hits("uniquelifecyclemodified", 0);
            try (var pdf = new PDDocument()) {
                PDPage page = new PDPage(); pdf.addPage(page);
                try (var text = new PDPageContentStream(pdf, page)) {
                    text.beginText(); text.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                    text.newLineAtOffset(50, 700); text.showText("uniquepdfverification orbital mechanics"); text.endText();
                }
                pdf.save(docs.resolve("generated.pdf").toFile());
            }
            json("update"); hits("uniquepdfverification", 1);
            run("benchmark", "--queries", "examples/queries.txt", "--iterations", "100", "--warmup", "10");
            Process server = new ProcessBuilder(command("serve", "--port", "0", "--hide-paths")).redirectError(workspace.resolve("server.err").toFile()).start();
            try {
                var reader = server.inputReader();
                String ready = java.util.concurrent.CompletableFuture.supplyAsync(() -> { try { return reader.readLine(); } catch (Exception e) { throw new RuntimeException(e); } }).get(30, TimeUnit.SECONDS);
                require(ready != null && ready.contains("loopback port"), "Server readiness");
                String port = ready.substring(ready.lastIndexOf(' ') + 1);
                var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
                for (String endpoint : List.of("/health", "/api/search?q=java", "/api/index/info", "/api/stats")) {
                    var response = client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + endpoint)).timeout(Duration.ofSeconds(20)).GET().build(), HttpResponse.BodyHandlers.ofString());
                    require(response.statusCode() == 200, endpoint);
                    JSON.readTree(response.body());
                    require(!response.body().contains(docs.toString()), "Absolute path exposed");
                    LOG.add("GET " + endpoint + " -> " + response.statusCode() + "\n" + response.body());
                    System.out.println("PASS GET " + endpoint);
                }
            } finally { server.destroy(); if (!server.waitFor(10, TimeUnit.SECONDS)) server.destroyForcibly(); }
            Path synthetic = Files.createDirectory(workspace.resolve("synthetic"));
            for (int i = 0; i < 2000; i++) Files.writeString(synthetic.resolve("doc" + i + ".txt"),
                    "java database indexing common concurrency distributed systems topic" + i + "\n" + "network routing database search ".repeat(8));
            require(json("index", synthetic.toString(), "--name", "synthetic").get("documents").asInt() == 2000, "Synthetic corpus count");
            run("benchmark", "--index", "synthetic", "--queries", "examples/queries.txt", "--iterations", "100", "--warmup", "10");
            Files.createDirectories(Path.of("target"));
            String report = String.join("\n", LOG).replace(workspace.toString().replace("\\", "\\\\"), "<temporary-workspace>").replace(workspace.toString(), "<temporary-workspace>");
            Files.writeString(Path.of("target/verification.txt"), report);
            System.out.println("All fresh-process checks passed; report: target/verification.txt");
        } finally {
            // The target comes directly from createTempDirectory, never from user input.
            try (var paths = Files.walk(workspace)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }
}
