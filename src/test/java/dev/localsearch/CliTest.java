package dev.localsearch;

import dev.localsearch.cli.SearchCli;
import dev.localsearch.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class CliTest {
    @TempDir Path temp;
    record Run(int code, String out, String err) {}
    Run run(String... command) {
        var cli = SearchCli.commandLine(); var out = new StringWriter(); var err = new StringWriter();
        cli.setOut(new PrintWriter(out, true)); cli.setErr(new PrintWriter(err, true));
        String[] args = new String[command.length + 4];
        args[0] = "--index-dir"; args[1] = temp.resolve("indexes").toString();
        args[2] = "--config"; args[3] = temp.resolve("config.properties").toString();
        System.arraycopy(command, 0, args, 4, command.length);
        int code = cli.execute(args); return new Run(code, out.toString(), err.toString());
    }
    @Test void executableCommandsAndJsonContract() throws Exception {
        assertEquals(0, run("--help").code()); assertTrue(run("--version").out().contains("1.0.0"));
        assertEquals(3, run("info").code()); assertEquals(2, run("missing-command").code());
        Path root = Files.createDirectory(temp.resolve("docs")); Files.writeString(root.resolve("test.md"), "java search engine");
        assertEquals(0, run("index", root.toString(), "--threads", "2").code());
        Run result = run("search", "java", "--json"); assertEquals(0, result.code(), result.err());
        assertEquals(1, Json.MAPPER.readTree(result.out()).get("totalHits").asInt()); assertFalse(result.out().contains("\u001b"));
        assertEquals(2, run("search", "java", "--top", "0").code());
        assertEquals(0, run("stats").code()); assertEquals(0, run("list-documents").code());
        assertEquals(1, Json.MAPPER.readTree(run("update").out()).get("unchanged").asInt());
        assertEquals(0, run("benchmark", "--iterations", "5", "--warmup", "1").code());
        assertEquals(0, run("delete-index", "--yes").code()); assertTrue(Files.exists(root.resolve("test.md")));
        assertEquals(3, run("info").code());
    }
    @Test void configurationAndSizeParsing() throws Exception {
        assertEquals(20 * 1024 * 1024, Configuration.fileSize("20MB"));
        assertThrows(IllegalArgumentException.class, () -> Configuration.fileSize("-1"));
        Files.writeString(temp.resolve("config.properties"), "search.top=1\nbm25.k1=1.5\nthreads=2\n");
        Path root = Files.createDirectory(temp.resolve("docs")); Files.writeString(root.resolve("a.txt"), "java"); Files.writeString(root.resolve("b.txt"), "java");
        assertEquals(0, run("index", root.toString()).code());
        assertEquals(1, Json.MAPPER.readTree(run("search", "java", "--json").out()).get("results").size());
        assertEquals(2, Json.MAPPER.readTree(run("search", "java", "--json", "--top", "2").out()).get("results").size());
    }
}
