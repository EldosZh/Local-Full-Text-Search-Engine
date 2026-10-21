package dev.localsearch;

import dev.localsearch.text.Tokenizer;
import dev.localsearch.search.query.*;
import dev.localsearch.search.BM25Scorer;
import dev.localsearch.benchmark.SearchBenchmark;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class TokenizerTest {
    final Tokenizer tokenizer = new Tokenizer();
    @Test void wordsAndIdentifiers() {
        var tokens = tokenizer.tokenize("Hello, world! 123 get_user_by_id getUserById HTTPServer");
        var terms = tokens.stream().map(Tokenizer.Token::term).toList();
        assertTrue(terms.containsAll(List.of("hello", "world", "123", "get_user_by_id", "getuserbyid", "get", "user", "by", "id", "http", "server")));
        assertEquals(6, tokenizer.length("Hello, world! 123 get_user_by_id getUserById HTTPServer"));
        assertEquals(1, tokens.stream().filter(t -> t.term().equals("user") && t.position() == 3).count());
    }
    @Test void unicodeAndOffsets() {
        String text = "Привет Әлем Қазақстан café cafe\u0301 ＪＡＶＡ";
        var tokens = tokenizer.tokenize(text);
        assertEquals(List.of("привет", "әлем", "қазақстан", "café", "café", "java"), tokens.stream().map(Tokenizer.Token::term).toList());
        for (var token : tokens) assertEquals(token.term(), Tokenizer.normalize(text.substring(token.start(), token.end())));
    }
    @Test void localeIndependent() {
        Locale previous = Locale.getDefault();
        try { Locale.setDefault(Locale.forLanguageTag("tr")); assertEquals("index", Tokenizer.normalize("INDEX")); }
        finally { Locale.setDefault(previous); }
    }
    @Test void emptyAndLongWords() {
        assertTrue(tokenizer.tokenize("").isEmpty());
        var tokens = tokenizer.tokenize("a".repeat(10000) + " end");
        assertEquals(1, tokens.size()); assertEquals(1, tokens.getFirst().position());
    }
    @Test void parserPrecedence() {
        Query query = new QueryParser("java OR python AND NOT mysql").parse();
        assertInstanceOf(Query.Or.class, query);
        assertInstanceOf(Query.And.class, ((Query.Or) query).right());
        assertInstanceOf(Query.Not.class, ((Query.And) ((Query.Or) query).right()).right());
        assertInstanceOf(Query.Or.class, new QueryParser("java database").parse());
        assertInstanceOf(Query.And.class, new QueryParser("database NOT mysql").parse());
        assertInstanceOf(Query.Phrase.class, new QueryParser("\"distributed systems\"").parse());
        assertTrue(((Query.Term) new QueryParser("distrib*").parse()).prefix());
    }
    @ParameterizedTest @ValueSource(strings = {"", "java AND", "OR java", "\"unclosed", "()", "(java", "java)", "*", "ja*va", "\"\"", "java AND OR python"})
    void invalidSyntax(String query) { assertThrows(IllegalArgumentException.class, () -> new QueryParser(query).parse()); }
    @Test void formulaAndPercentiles() {
        double expected = Math.log(1 + 1.5 / 1.5) * 3 * 2.2 / (3 + 1.2 * (.25 + .75 * 2));
        assertEquals(expected, new BM25Scorer().score(3, 1, 2, 10, 5), 1e-12);
        assertEquals(0, new BM25Scorer().score(0, 0, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new BM25Scorer(0, 1));
        assertEquals(3, SearchBenchmark.percentile(new double[]{1, 2, 3, 4, 5}, .5));
        assertEquals(5, SearchBenchmark.percentile(new double[]{1, 2, 3, 4, 5}, .95));
    }
}
