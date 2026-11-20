package io.github.rkaushikdev.trammelworks.query;

/**
 * A node of a parsed filter.
 *
 * <p>The tree is kept apart from the parser so that its two consumers can be written against it
 * rather than against each other: the evaluator walks a record in memory, and the planner turns the
 * same tree into SQL the archive can index. Every node renders back to something the parser
 * accepts, because the reports quote the filter they ran, and a filter that reads back differently
 * from what was typed is how somebody ends up arguing about which records a report covered.
 */
public interface Node {

    /** The node as filter text the parser reads back to the same tree. */
    String render();
}
