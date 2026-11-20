package io.github.rkaushikdev.trammelworks.query;

/** The inverse of the inner condition. */
public record Not(Node inner) implements Node {

    @Override
    public String render() {
        return "not " + inner.render();
    }
}
