package io.github.rkaushikdev.trammelworks.query;

import io.github.rkaushikdev.trammelworks.QueryError;

/** What a comparison does. */
public enum Op {
    EQ("="),
    NE("!="),
    MATCH("~"),
    NOT_MATCH("!~"),
    LT("<"),
    LE("<="),
    GT(">"),
    GE(">=");

    private final String value;

    Op(String value) {
        this.value = value;
    }

    /** The operator as it is written. */
    public String value() {
        return value;
    }

    /** The operator written {@code text}. */
    public static Op of(String text) {
        for (Op op : values()) {
            if (op.value.equals(text)) {
                return op;
            }
        }
        throw new QueryError("unknown operator", text);
    }

    @Override
    public String toString() {
        return value;
    }
}
