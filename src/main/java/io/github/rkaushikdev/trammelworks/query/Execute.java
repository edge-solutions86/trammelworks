package io.github.rkaushikdev.trammelworks.query;

import io.github.rkaushikdev.trammelworks.QueryError;
import io.github.rkaushikdev.trammelworks.archive.ArchiveDb;
import io.github.rkaushikdev.trammelworks.archive.Repository;
import io.github.rkaushikdev.trammelworks.archive.Row;
import io.github.rkaushikdev.trammelworks.archive.SqlFailure;
import io.github.rkaushikdev.trammelworks.archive.StoredRecord;
import java.util.List;
import java.util.Optional;

/**
 * Running a filter against the archive: plan, fetch, then apply whatever the plan could not push
 * into SQL. The residual is not optional; a caller that skipped it would get more rows than the
 * filter asked for, and every one of them would look like a match.
 *
 * <p>The fetch is bounded even when the filter is not. A support engineer who types {@code message
 * ~ e} against a site holding four million rows gets an answer and a warning, not a laptop that
 * stops responding, so the read stops at a ceiling and says it was truncated.
 */
public final class Execute {

    /** The most rows a single search will pull out of SQLite before giving up. */
    public static final int DEFAULT_SCAN_CEILING = 200_000;

    private Execute() {}

    /** A first page, under the default ceiling. */
    public static SearchResult search(ArchiveDb connection, Query query) {
        return search(connection, query, DEFAULT_SCAN_CEILING, Optional.empty());
    }

    /** A first page, under {@code ceiling}. */
    public static SearchResult search(ArchiveDb connection, Query query, int ceiling) {
        return search(connection, query, ceiling, Optional.empty());
    }

    /**
     * Plan, fetch and finish a filter. {@code after} resumes a previous page. The cursor handed back
     * is the position of the last record actually returned, not the last row read, because the rows
     * the residual dropped were never shown to anybody and resuming past them would skip whatever
     * comes next.
     */
    public static SearchResult search(ArchiveDb connection, Query query, int ceiling, Optional<Cursor> after) {
        if (ceiling < 1) {
            throw new QueryError("scan ceiling must be at least one", Integer.toString(ceiling));
        }
        Plan built = Plan.plan(query, after);
        SearchResult result = new SearchResult(built.sql());
        for (Row row : fetch(connection, built, ceiling)) {
            result.scan();
            StoredRecord record = Repository.rowToRecord(row);
            if (built.residual().isPresent()
                    && !Evaluate.matches(built.residual().get(), record)) {
                result.drop();
                continue;
            }
            result.keep(record, new Cursor(record.bestAt().epochNs(), row.getLong("id")));
            if (query.limit().isPresent() && result.count() >= query.limit().getAsInt()) {
                return result;
            }
        }
        result.finish(result.scanned() >= ceiling);
        return result;
    }

    private static List<Row> fetch(ArchiveDb connection, Plan built, int ceiling) {
        String statement = built.sql();
        if (built.limit().isEmpty() || !built.fullyPushed()) {
            statement = statement + " LIMIT " + ceiling;
        }
        try {
            return connection.query(statement, built.allParams().toArray());
        } catch (SqlFailure exc) {
            throw new QueryError("the archive refused the query", exc.getMessage(), exc);
        }
    }

    /** How many records match, ignoring any limit on the query, under the default ceiling. */
    public static int countMatching(ArchiveDb connection, Query query) {
        return countMatching(connection, query, DEFAULT_SCAN_CEILING);
    }

    /** How many records match, ignoring any limit on the query. */
    public static int countMatching(ArchiveDb connection, Query query, int ceiling) {
        return search(connection, query.unlimited(), ceiling).count();
    }

    /** A line describing how a filter will be run, for the doctor command. */
    public static String explain(Query query) {
        Plan built = Plan.plan(query);
        if (built.fullyPushed()) {
            return "sql: " + built.whereSql();
        }
        return "sql: " + built.whereSql() + "; then in memory: "
                + built.residual().map(Node::render).orElse("");
    }
}
