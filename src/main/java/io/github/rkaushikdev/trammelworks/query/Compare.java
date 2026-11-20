package io.github.rkaushikdev.trammelworks.query;

/**
 * One field against one value.
 *
 * @param field the field, validated and lower cased
 * @param op the comparison
 * @param value the value compared against
 */
public record Compare(String field, Op op, String value) implements Node {

    /** Validates the field name. */
    public Compare {
        field = Ast.checkField(field);
    }

    @Override
    public String render() {
        return field + " " + op.value() + " " + Ast.quote(value);
    }
}
