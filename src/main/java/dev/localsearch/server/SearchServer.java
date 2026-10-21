package dev.localsearch.server;

import com.sun.net.httpserver.*;
import dev.localsearch.index.InvertedIndex;
import dev.localsearch.search.*;
import dev.localsearch.storage.IndexStorage;
import dev.localsearch.util.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;

/** Loopback-only, read-only API. Each request observes one committed disk snapshot. */
public final class SearchServer implements AutoCloseable {
    private final HttpServer server;
    private final ExecutorService executor = Executors.newFixedThreadPool(8);
    private final Path directory;
    private final boolean hidePaths;
    private final BM25Scorer scorer;
    private volatile InvertedIndex cached;
    private volatile String generation;
    public SearchServer(Path directory, int port, boolean hidePaths, BM25Scorer scorer) throws IOException {
        this.directory = directory; this.hidePaths = hidePaths; this.scorer = scorer;
        if (port < 0 || port > 65535) throw new IllegalArgumentException("Port must be between 0 and 65535");
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 32);
        server.setExecutor(executor); server.createContext("/", this::handle);
    }
    public void start() { server.start(); }
    public int port() { return server.getAddress().getPort(); }
    private synchronized InvertedIndex snapshot() throws IOException {
        if (!new IndexStorage().exists(directory)) throw new IndexStorage.MissingIndex();
        String current = java.nio.file.Files.readString(directory.resolve("CURRENT"));
        if (!current.equals(generation)) { cached = new IndexStorage().load(directory); generation = current; }
        return cached;
    }
    private void handle(HttpExchange exchange) throws IOException {
        try {
            if (!exchange.getRequestMethod().equals("GET")) { send(exchange, 405, Map.of("error", "Only GET is supported")); return; }
            String route = exchange.getRequestURI().getPath();
            if (route.equals("/health")) { send(exchange, 200, Map.of("status", "ok")); return; }
            if (!Set.of("/api/search", "/api/index/info", "/api/stats").contains(route)) { send(exchange, 404, Map.of("error", "Endpoint not found")); return; }
            Map<String, String> params = parameters(exchange.getRequestURI().getRawQuery());
            Object response;
            if (route.equals("/api/search")) {
                String query = params.get("q");
                if (query == null || query.isBlank()) throw new IllegalArgumentException("Missing query parameter q");
                var options = new SearchEngine.Options(Integer.parseInt(params.getOrDefault("limit", "10")), params.get("type"), params.get("path"), Double.parseDouble(params.getOrDefault("minScore", "0")), hidePaths);
                response = new SearchEngine(snapshot(), scorer).search(query, options);
            } else if (route.equals("/api/index/info")) response = IndexInfo.info(snapshot(), hidePaths);
            else response = IndexInfo.stats(snapshot(), hidePaths);
            send(exchange, 200, response);
        } catch (IllegalArgumentException e) { send(exchange, 400, Map.of("error", e.getMessage())); }
        catch (IndexStorage.MissingIndex e) { send(exchange, 404, Map.of("error", e.getMessage())); }
        catch (Exception e) { org.slf4j.LoggerFactory.getLogger(SearchServer.class).debug("API request failed", e); send(exchange, 500, Map.of("error", "Cannot read index; inspect it using the CLI")); }
        finally { exchange.close(); }
    }
    private static Map<String, String> parameters(String raw) {
        Map<String, String> params = new HashMap<>();
        if (raw == null) return params;
        if (raw.length() > 16000) throw new IllegalArgumentException("Query parameters too long");
        for (String pair : raw.split("&")) {
            String[] pieces = pair.split("=", 2);
            params.put(URLDecoder.decode(pieces[0], StandardCharsets.UTF_8), pieces.length == 2 ? URLDecoder.decode(pieces[1], StandardCharsets.UTF_8) : "");
        }
        return params;
    }
    private static void send(HttpExchange exchange, int status, Object body) throws IOException {
        byte[] bytes = Json.encode(body).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }
    @Override public void close() { server.stop(0); executor.shutdownNow(); }
}
