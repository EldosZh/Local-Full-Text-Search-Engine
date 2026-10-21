package dev.localsearch.search.query;

import java.util.List;

public sealed interface Query {
    record Term(String value, boolean prefix) implements Query {}
    record Phrase(List<String> terms) implements Query { public Phrase { terms = List.copyOf(terms); } }
    record And(Query left, Query right) implements Query {}
    record Or(Query left, Query right) implements Query {}
    record Not(Query child) implements Query {}
}
