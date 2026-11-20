package io.github.rkaushikdev.trammelworks.query;

import io.github.rkaushikdev.trammelworks.LogRecord;
import io.github.rkaushikdev.trammelworks.QueryError;
import io.github.rkaushikdev.trammelworks.archive.StoredRecord;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Deciding whether one record matches a filter.
 *
 * <p>There are two things that run a filter and this is the simpler of them. The planner turns the
 * tree into SQL so the archive can use its indexes; this walks the tree against a record already in
 * hand. The archive is queried through the planner, because a site holds four million rows, and the
 * live tail is filtered here, because those records have not been stored yet. Keeping both also
 * gives the planner an oracle: a property test can run a filter both ways over the same records and
 * demand the same answer.
 *
 * <p>Comparisons are string comparisons except on the two time fields, and {@code ~} is "contains,
 * ignoring case" rather than a regular expression. A support engineer typing a filter into a
 * terminal wants substring search.
 *
 * <p>A record is either a {@link StoredRecord} read back from the archive or a {@link LogRecord}
 * still on its way there. A live record has no class and no stored confidence yet, so {@code class}
 * reads as empty and {@code confidence} as absent.
 */
public final class Evaluate {

    private Evaluate() {}

    /** One field off a record, or empty where it has no such thing. */
    public static Optional<String> fieldValue(Object record, String field) {
        if (field.startsWith(Ast.LABEL_PREFIX)) {
            return Optional.ofNullable(labels(record).get(field.substring(Ast.LABEL_PREFIX.length())));
        }
        return switch (record) {
            case StoredRecord stored ->
                Optional.of(
                        switch (field) {
                            case "site" -> stored.site();
                            case "unit" -> stored.unit();
                            case "class" -> stored.klass();
                            case "stream" -> stored.stream().value();
                            case "message" -> stored.message();
                            case "confidence" -> stored.confidence();
                            default -> throw new QueryError("unknown field", field);
                        });
            case LogRecord live ->
                switch (field) {
                    case "site" -> Optional.of(live.site());
                    case "unit" -> Optional.of(live.unit());
                    case "class" -> Optional.of("");
                    case "stream" -> Optional.of(live.stream().value());
                    case "message" -> Optional.of(live.message());
                    case "confidence" -> Optional.empty();
                    default -> throw new QueryError("unknown field", field);
                };
            default -> throw unfilterable(record);
        };
    }

    private static Map<String, String> labels(Object record) {
        return switch (record) {
            case StoredRecord stored -> stored.labels();
            case LogRecord live -> live.labels();
            default -> throw unfilterable(record);
        };
    }

    private static IllegalArgumentException unfilterable(Object record) {
        return new IllegalArgumentException("not a record a filter can read: "
                + (record == null ? "null" : record.getClass().getName()));
    }

    /** One comparison, with the case rules the language promises. */
    public static boolean compareValues(String left, Op op, String right) {
        if (op == null) {
            throw new QueryError("unknown operator", "None");
        }
        return switch (op) {
            case EQ -> left.equals(right);
            case NE -> !left.equals(right);
            case MATCH -> fold(left).contains(fold(right));
            case NOT_MATCH -> !fold(left).contains(fold(right));
            case LT -> compare(left, right) < 0;
            case LE -> compare(left, right) <= 0;
            case GT -> compare(left, right) > 0;
            case GE -> compare(left, right) >= 0;
        };
    }

    private static String fold(String text) {
        return text.toLowerCase(Locale.ROOT);
    }

    private static int compare(String left, String right) {
        return io.github.rkaushikdev.trammelworks.Json.compareCodePoints(left, right);
    }

    /** The time a window is measured against: the corrected time where there is one. */
    public static long recordInstant(Object record) {
        return switch (record) {
            case StoredRecord stored -> stored.bestAt().epochNs();
            case LogRecord live -> live.bestAt().epochNs();
            default -> throw unfilterable(record);
        };
    }

    /** Walk the tree against one record; no tree matches everything. */
    public static boolean matches(Optional<Node> node, Object record) {
        return node.isEmpty() || matches(node.get(), record);
    }

    /** Walk the tree against one record. */
    public static boolean matches(Node node, Object record) {
        return switch (node) {
            case And both -> matches(both.left(), record) && matches(both.right(), record);
            case Or either -> matches(either.left(), record) || matches(either.right(), record);
            case Not inverse -> !matches(inverse.inner(), record);
            case Since since -> recordInstant(record) >= since.atNs();
            case Until until -> recordInstant(record) <= until.atNs();
            case Compare compare -> {
                Optional<String> found = fieldValue(record, compare.field());
                // An absent label matches only a negated test. Treating it as an empty string
                // would make "label.lane != 3" true for every record that has no lane at all,
                // which reads as a claim about lanes.
                yield found.isEmpty()
                        ? compare.op() == Op.NE || compare.op() == Op.NOT_MATCH
                        : compareValues(found.get(), compare.op(), compare.value());
            }
            case InSet set -> {
                Optional<String> found = fieldValue(record, set.field());
                // Same rule: a label that is absent is outside any list somebody named.
                yield found.isEmpty() ? set.negated() : set.values().contains(found.get()) != set.negated();
            }
            default ->
                throw new QueryError("unknown filter node", node.getClass().getSimpleName());
        };
    }

    /** Filter a list, honouring the limit. */
    public static <T> List<T> run(Query query, List<T> records) {
        java.util.stream.Stream<T> kept = records.stream().filter(record -> matches(query.where(), record));
        if (query.limit().isPresent()) {
            kept = kept.limit(query.limit().getAsInt());
        }
        return kept.toList();
    }

    /** How many match, ignoring the limit. */
    public static int count(Query query, List<?> records) {
        return (int) records.stream()
                .filter(record -> matches(query.where(), record))
                .count();
    }
}
