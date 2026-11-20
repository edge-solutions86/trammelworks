package io.github.rkaushikdev.trammelworks.query;

/** Either side. */
public record Or(Node left, Node right) implements Node {

    @Override
    public String render() {
        return "(" + left.render() + " or " + right.render() + ")";
    }
}
