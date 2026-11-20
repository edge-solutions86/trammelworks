package io.github.rkaushikdev.trammelworks.query;

import io.github.rkaushikdev.trammelworks.QueryError;
import java.util.List;
import java.util.stream.Collectors;

/**
 * One field against a set of values.
 *
 * @param field the field, validated and lower cased
 * @param values the values, in the order written
 * @param negated whether this is {@code not in}
 */
public record InSet(String field, List<String> values, boolean negated) implements Node {

    /** Validates the field and refuses an empty list. */
    public InSet {
        field = Ast.checkField(field);
        values = List.copyOf(values);
        if (values.isEmpty()) {
            throw new QueryError("an in list cannot be empty", field);
        }
    }

    /** A plain {@code in}. */
    public InSet(String field, List<String> values) {
        this(field, values, false);
    }

    @Override
    public String render() {
        String inner = values.stream().map(Ast::quote).collect(Collectors.joining(", "));
        return field + " " + (negated ? "not in" : "in") + " (" + inner + ")";
    }
}
