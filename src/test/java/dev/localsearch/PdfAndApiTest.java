package dev.localsearch;

import dev.localsearch.index.*;
import dev.localsearch.search.*;
import dev.localsearch.server.SearchServer;
import dev.localsearch.storage.IndexStorage;
import dev.localsearch.util.Json;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.font.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.net.*;
import java.net.http.*;
import static org.junit.jupiter.api.Assertions.*;

class PdfAndApiTest {
    @TempDir Path temp;
    @Test void generatedPdfSearchAndEmptyPdfWarning() throws Exception {
        Path root = Files.createDirectory(temp.resolve("docs")), dir = temp.resolve("index");
        try (PDDocument pdf = new PDDocument()) {
            PDPage page = new PDPage(); pdf.addPage(page);
            try (var stream = new PDPageContentStream(pdf, page)) {
                stream.beginText(); stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                stream.newLineAtOffset(50, 700); stream.showText("searchable orbital mechanics"); stream.endText();
            }
            pdf.save(root.resolve("paper.pdf").toFile());
        }
        try (PDDocument pdf = new PDDocument()) { pdf.addPage(new PDPage()); pdf.save(root.resolve("scan.pdf").toFile()); }
        var report = new IndexWriter().index(root, dir, IndexWriter.Options.defaults());
        assertEquals(1, report.added()); assertEquals(1, report.skipped());
        assertTrue(report.warnings().getFirst().contains("OCR"));
        assertEquals("paper.pdf", new SearchEngine(new IndexStorage().load(dir)).search("orbital", SearchEngine.Options.defaults()).results().getFirst().fileName());
    }
    @Test void apiValidationPrivacyAndSnapshotRefresh() throws Exception {
        Path root = Files.createDirectory(temp.resolve("docs")), dir = temp.resolve("index");
        try (var server = new SearchServer(dir, 0, true, new BM25Scorer())) {
            server.start(); String base = "http://localhost:" + server.port();
            assertEquals(200, get(base + "/health").statusCode());
            assertEquals(400, get(base + "/api/search").statusCode());
            assertEquals(400, get(base + "/api/search?q=java&limit=0").statusCode());
            assertEquals(404, get(base + "/api/search?q=java").statusCode());
            Files.writeString(root.resolve("sample.java"), "java concurrency");
            new IndexWriter().index(root, dir, IndexWriter.Options.defaults());
            var search = get(base + "/api/search?q=java"); assertEquals(200, search.statusCode());
            var body = Json.MAPPER.readTree(search.body()); assertEquals(1, body.get("totalHits").asInt());
            assertEquals("sample.java", body.get("results").get(0).get("path").asText());
            assertFalse(search.body().contains(temp.toString()));
            assertEquals(".", Json.MAPPER.readTree(get(base + "/api/index/info").body()).get("root").asText());
            assertEquals(200, get(base + "/api/stats").statusCode());
            assertEquals(400, get(base + "/api/search?q=java&type=exe").statusCode());
            assertEquals(404, get(base + "/unknown").statusCode());
            var post = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(base + "/api/search?q=java")).POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(405, post.statusCode());
            Files.writeString(root.resolve("sample.java"), "updated networking"); new IndexWriter().index(root, dir, IndexWriter.Options.defaults());
            assertEquals(0, Json.MAPPER.readTree(get(base + "/api/search?q=java").body()).get("totalHits").asInt());
        }
    }
    HttpResponse<String> get(String url) throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(url)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }
}
