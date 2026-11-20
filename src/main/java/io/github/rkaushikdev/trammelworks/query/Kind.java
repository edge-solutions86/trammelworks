package io.github.rkaushikdev.trammelworks.query;

/** What a token is. */
public enum Kind {
    WORD("word"),
    STRING("string"),
    NUMBER("number"),
    OPERATOR("operator"),
    LPAREN("lparen"),
    RPAREN("rparen"),
    COMMA("comma"),
    END("end");

    private final String value;

    Kind(String value) {
        this.value = value;
    }

    /** The name a token's description uses. */
    public String value() {
        return value;
    }

    @Override
    public String toString() {
        return value;
    }
}
