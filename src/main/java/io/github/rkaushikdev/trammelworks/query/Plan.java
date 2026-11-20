package io.github.rkaushikdev.trammelworks.query;

import io.github.rkaushikdev.trammelworks.QueryError;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.stream.Collectors;

/**
 * What to ask SQL for, and what still has to be checked afterwards.
 *
 * <p>Not every filter can be pushed into SQL. Labels are stored as a JSON blob, and rather than
 * depend on the JSON functions being compiled into whatever SQLite the site image carries, a label
 * predicate is left for the evaluator to apply to the rows that come back. How it is left differs
 * across the tree: under an {@code and} the two sides are independent, so the non-label side is
 * pushed and the label side becomes the residual; under an {@code or} or a {@code not} they are not,
 * because pushing a partial constraint would drop rows that match through the other half, so the
 * whole subtree becomes residual and SQL sees no constraint from it. Getting that wrong does not
 * produce an error; it produces a short answer.
 *
 * <p>Every plan carries its residual, and the executor is required to apply it.
 *
 * @param whereSql the SQL condition, {@code 1} when there is none
 * @param params the values bound to the condition's placeholders
 * @param limit the query's limit
 * @param residual what the evaluator still has to check
 * @param after where the previous page stopped
 */
public record Plan(
        String whereSql, List<Object> params, OptionalInt limit, Optional<Node> residual, Optional<Cursor> after) {

    /**
     * Filter field to archive column. {@code message} is deliberately not indexed: a substring
     * search cannot use an index anyway.
     */
    public static final Map<String, String> COLUMNS = columns();

    /** The time a record is filed under: corrected where there is a correction. */
    public static final String TIME_COLUMN = "COALESCE(corrected_ns, observed_ns)";

    private static final Map<Op, String> SQL_OPERATORS =
            Map.of(Op.EQ, "=", Op.NE, "!=", Op.LT, "<", Op.LE, "<=", Op.GT, ">", Op.GE, ">=");

    /** Copies the parameters. */
    public Plan {
        params = List.copyOf(params);
    }

    private static Map<String, String> columns() {
        Map<String, String> out = new LinkedHashMap<>();
        out.put("site", "site");
        out.put("unit", "unit");
        out.put("class", "klass");
        out.put("stream", "stream");
        out.put("message", "message");
        out.put("confidence", "confidence");
        return java.util.Collections.unmodifiableMap(out);
    }

    /** Whether rows that come back still have to be checked. */
    public boolean needsPostFilter() {
        return residual.isPresent();
    }

    /** Whether SQL alone decides which rows match. */
    public boolean fullyPushed() {
        return residual.isEmpty();
    }

    /**
     * The filter and the cursor together. The cursor is not part of the filter the user typed and
     * it is not optional either: it is what makes the next page the next page, so no caller builds
     * the statement and forgets it.
     */
    public String fullWhere() {
        return after.isEmpty() ? whereSql : "(" + whereSql + ") AND " + Cursor.cursorSql(TIME_COLUMN);
    }

    /** The condition's values followed by the cursor's. */
    public List<Object> allParams() {
        List<Object> out = new ArrayList<>(params);
        after.ifPresent(cursor -> out.addAll(Cursor.cursorParams(cursor)));
        return out;
    }

    /** The whole statement selecting every column. */
    public String sql() {
        return sql("*");
    }

    /**
     * The whole statement, ordered the way the archive is read. The limit is applied in SQL only
     * when nothing is left to check; otherwise the post filter would be handed a short list.
     */
    public String sql(String columns) {
        String order = " ORDER BY " + TIME_COLUMN + " ASC, id ASC";
        String tail = limit.isPresent() && fullyPushed() ? " LIMIT " + limit.getAsInt() : "";
        return "SELECT " + columns + " FROM records WHERE " + fullWhere() + order + tail;
    }

    /** True when this subtree can be expressed entirely in SQL. */
    public static boolean isPushable(Node node) {
        return switch (node) {
            case Compare compare -> !compare.field().startsWith(Ast.LABEL_PREFIX);
            case InSet set -> !set.field().startsWith(Ast.LABEL_PREFIX);
            case And both -> isPushable(both.left()) && isPushable(both.right());
            case Or either -> isPushable(either.left()) && isPushable(either.right());
            case Not inverse -> isPushable(inverse.inner());
            default -> true;
        };
    }

    /** Work out the statement and what the evaluator still has to do. */
    public static Plan plan(Query query) {
        return plan(query, Optional.empty());
    }

    /** Work out the statement for the page after {@code after}. */
    public static Plan plan(Query query, Optional<Cursor> after) {
        if (query.where().isEmpty()) {
            return new Plan("1", List.of(), query.limit(), Optional.empty(), after);
        }
        Node[] parts = split(query.where().get());
        Optional<Node> residual = Optional.ofNullable(parts[1]);
        if (parts[0] == null) {
            return new Plan("1", List.of(), query.limit(), residual, after);
        }
        List<Object> params = new ArrayList<>();
        String whereSql = nodeSql(parts[0], params);
        return new Plan(whereSql, params, query.limit(), residual, after);
    }

    private static String compareSql(Compare node, List<Object> params) {
        String column = COLUMNS.get(node.field());
        if (node.op() == Op.MATCH || node.op() == Op.NOT_MATCH) {
            params.add("%" + escapeLike(node.value()) + "%");
            String keyword = node.op() == Op.MATCH ? "LIKE" : "NOT LIKE";
            return column + " " + keyword + " ? ESCAPE '\\'";
        }
        String operator = SQL_OPERATORS.get(node.op());
        if (operator == null) {
            throw new QueryError("operator cannot be planned", String.valueOf(node.op()));
        }
        params.add(node.value());
        return column + " " + operator + " ?";
    }

    /** Escape the wildcards LIKE would otherwise read as pattern syntax. */
    private static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private static String nodeSql(Node node, List<Object> params) {
        return switch (node) {
            case Compare compare -> compareSql(compare, params);
            case InSet set -> {
                params.addAll(set.values());
                String holes = set.values().stream().map(value -> "?").collect(Collectors.joining(", "));
                yield COLUMNS.get(set.field()) + " " + (set.negated() ? "NOT IN" : "IN") + " (" + holes + ")";
            }
            case Since since -> {
                params.add(since.atNs());
                yield TIME_COLUMN + " >= ?";
            }
            case Until until -> {
                params.add(until.atNs());
                yield TIME_COLUMN + " <= ?";
            }
            case And both -> {
                String left = nodeSql(both.left(), params);
                yield "(" + left + " AND " + nodeSql(both.right(), params) + ")";
            }
            case Or either -> {
                String left = nodeSql(either.left(), params);
                yield "(" + left + " OR " + nodeSql(either.right(), params) + ")";
            }
            case Not inverse -> "(NOT " + nodeSql(inverse.inner(), params) + ")";
            default ->
                throw new QueryError("node cannot be planned", node.getClass().getSimpleName());
        };
    }

    /** Divide a tree into the part SQL can take and the part it cannot; either may be null. */
    private static Node[] split(Node node) {
        if (isPushable(node)) {
            return new Node[] {node, null};
        }
        if (node instanceof And both) {
            Node[] left = split(both.left());
            Node[] right = split(both.right());
            return new Node[] {join(left[0], right[0]), join(left[1], right[1])};
        }
        // An or or a not that is not wholly pushable cannot be split at all.
        return new Node[] {null, node};
    }

    private static Node join(Node left, Node right) {
        if (left == null) {
            return right;
        }
        if (right == null) {
            return left;
        }
        return new And(left, right);
    }
}
