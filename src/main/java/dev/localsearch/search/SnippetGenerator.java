package dev.localsearch.search;

import dev.localsearch.text.Tokenizer;
import java.util.*;

public final class SnippetGenerator {
    public record Highlight(int start, int end) {}
    public record Snippet(String text, List<Highlight> highlights) {}
    public Snippet generate(String text, Set<String> terms) {
        var matches = new Tokenizer().tokenize(text).stream().filter(t -> terms.contains(t.term())).toList();
        int hit = matches.isEmpty() ? 0 : matches.getFirst().start();
        int start = Math.max(0, hit - 80), end = Math.min(text.length(), hit + 160);
        while (start > 0 && start < hit && !Character.isWhitespace(text.charAt(start - 1))) start++;
        while (end < text.length() && !Character.isWhitespace(text.charAt(end))) end++;
        // A huge identifier should not make a snippet unbounded.
        end = Math.min(end, Math.min(text.length(), hit + 320));
        String prefix = start > 0 ? "..." : "";
        String snippet = prefix + text.substring(start, end) + (end < text.length() ? "..." : "");
        Set<Highlight> highlights = new LinkedHashSet<>();
        for (var token : matches) if (token.start() >= start && token.end() <= end)
            highlights.add(new Highlight(prefix.length() + token.start() - start, prefix.length() + token.end() - start));
        return new Snippet(snippet, List.copyOf(highlights));
    }
}
