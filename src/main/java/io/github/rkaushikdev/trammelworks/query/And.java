package io.github.rkaushikdev.trammelworks.query;

/** Both sides. */
public record And(Node left, Node right) implements Node {

    @Override
    public String render() {
        return "(" + left.render() + " and " + right.render() + ")";
    }
}
