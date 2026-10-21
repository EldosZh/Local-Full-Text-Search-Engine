package dev.localsearch.text;

import java.text.Normalizer;
import java.util.*;
import java.util.regex.Pattern;

/** Positions count lexical words, while identifier aliases share their parent's position. */
public final class Tokenizer {
    private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{N}_][\\p{L}\\p{M}\\p{N}_]*");
    private static final Pattern PART = Pattern.compile("_+|(?<=[\\p{Ll}\\p{N}])(?=\\p{Lu})|(?<=\\p{Lu})(?=\\p{Lu}\\p{Ll})");
    public record Token(String term, int position, int start, int end) {}
    public static String normalize(String text) {
        return Normalizer.normalize(text, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
    }
    public List<Token> tokenize(String text) {
        List<Token> result = new ArrayList<>();
        var matcher = WORD.matcher(text);
        int position = 0;
        while (matcher.find()) {
            String raw = matcher.group();
            // Bound pathological identifiers, but retain the positional gap.
            if (raw.length() <= 256) {
                Set<String> terms = new LinkedHashSet<>();
                terms.add(normalize(raw));
                for (String part : PART.split(raw)) if (!part.isBlank()) terms.add(normalize(part));
                for (String term : terms) result.add(new Token(term, position, matcher.start(), matcher.end()));
            }
            position++;
        }
        return result;
    }
    public int length(String text) {
        int count = 0;
        var matcher = WORD.matcher(text);
        while (matcher.find()) count++;
        return count;
    }
    public List<String> words(String text) {
        List<String> words = new ArrayList<>();
        var matcher = WORD.matcher(text);
        while (matcher.find()) {
            if (matcher.group().length() > 256) throw new IllegalArgumentException("Query token exceeds 256 characters");
            words.add(normalize(matcher.group()));
        }
        return words;
    }
}
