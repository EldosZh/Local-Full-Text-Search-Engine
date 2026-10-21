package dev.localsearch.search.query;

import java.util.*;

public final class QueryLexer {
    public enum Kind { WORD, PHRASE, AND, OR, NOT, LEFT, RIGHT, END }
    public record Lexeme(Kind kind, String text) {}
    public List<Lexeme> lex(String input) {
        if (input == null || input.isBlank()) throw new IllegalArgumentException("Query must not be blank");
        if (input.length() > 4096) throw new IllegalArgumentException("Query exceeds 4096 characters");
        List<Lexeme> result = new ArrayList<>();
        for (int i = 0; i < input.length();) {
            char c = input.charAt(i);
            if (Character.isWhitespace(c)) { i++; continue; }
            if (c == '(' || c == ')') { result.add(new Lexeme(c == '(' ? Kind.LEFT : Kind.RIGHT, String.valueOf(c))); i++; continue; }
            if (c == '"') {
                int end = input.indexOf('"', i + 1);
                if (end < 0) throw new IllegalArgumentException("Unclosed quoted phrase");
                result.add(new Lexeme(Kind.PHRASE, input.substring(i + 1, end))); i = end + 1; continue;
            }
            int start = i;
            while (i < input.length() && !Character.isWhitespace(input.charAt(i)) && "()\"".indexOf(input.charAt(i)) < 0) i++;
            String word = input.substring(start, i);
            Kind kind = switch (word) { case "AND" -> Kind.AND; case "OR" -> Kind.OR; case "NOT" -> Kind.NOT; default -> Kind.WORD; };
            result.add(new Lexeme(kind, word));
        }
        if (result.size() > 256) throw new IllegalArgumentException("Query exceeds 256 syntax tokens");
        result.add(new Lexeme(Kind.END, "")); return List.copyOf(result);
    }
}
