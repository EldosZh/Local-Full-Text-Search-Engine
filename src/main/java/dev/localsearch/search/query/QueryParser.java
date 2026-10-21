package dev.localsearch.search.query;

import dev.localsearch.text.Tokenizer;
import java.util.List;
import static dev.localsearch.search.query.QueryLexer.Kind.*;

/** NOT > AND (including adjacent NOT) > OR (including implicit whitespace OR). */
public final class QueryParser {
    private final List<QueryLexer.Lexeme> tokens;
    private int cursor;
    private final Tokenizer tokenizer = new Tokenizer();
    public QueryParser(String input) { tokens = new QueryLexer().lex(input); }
    public Query parse() {
        Query query = or();
        if (!at(END)) throw new IllegalArgumentException("Unexpected token: " + tokens.get(cursor).text());
        return query;
    }
    private Query or() {
        Query left = and();
        while (at(OR) || at(WORD) || at(PHRASE) || at(LEFT)) {
            if (at(OR)) cursor++;
            left = new Query.Or(left, and());
        }
        return left;
    }
    private Query and() {
        Query left = unary();
        while (at(AND) || at(NOT)) {
            if (at(AND)) cursor++;
            left = new Query.And(left, unary());
        }
        return left;
    }
    private Query unary() {
        if (at(NOT)) { cursor++; return new Query.Not(unary()); }
        if (at(LEFT)) {
            cursor++; Query inner = or();
            if (!at(RIGHT)) throw new IllegalArgumentException("Missing closing parenthesis");
            cursor++; return inner;
        }
        var token = tokens.get(cursor);
        if (token.kind() == PHRASE) {
            cursor++; var words = tokenizer.words(token.text());
            if (words.isEmpty()) throw new IllegalArgumentException("Empty phrase");
            return new Query.Phrase(words);
        }
        if (token.kind() != WORD) throw new IllegalArgumentException("Expected term, phrase or parenthesis");
        cursor++;
        String value = token.text(); boolean prefix = value.endsWith("*");
        if (prefix) value = value.substring(0, value.length() - 1);
        if (value.contains("*")) throw new IllegalArgumentException("Only a trailing prefix wildcard is supported");
        var words = tokenizer.words(value);
        if (words.size() != 1) throw new IllegalArgumentException("Expected one word; quote phrases or separate terms with spaces");
        return new Query.Term(words.getFirst(), prefix);
    }
    private boolean at(QueryLexer.Kind kind) { return tokens.get(cursor).kind() == kind; }
}
