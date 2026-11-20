package io.github.rkaushikdev.trammelworks.archive;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * One open connection to an archive file.
 *
 * <p>Statements run in autocommit unless they are inside {@link #transaction}, which wraps its work
 * in one real transaction: a commit per record on an SD card is the difference between a pass that
 * finishes and one the operator kills. The number of transactions opened is kept, because "far
 * fewer transactions than records" is a property worth testing.
 */
public final class ArchiveDb implements AutoCloseable {

    private final Connection connection;
    private final Path path;
    private int transactions;
    private boolean inTransaction;

    ArchiveDb(Connection connection, Path path) {
        this.connection = connection;
        this.path = path;
    }

    /** The archive file. */
    public Path path() {
        return path;
    }

    /** How many transactions have been opened on this connection. */
    public int transactions() {
        return transactions;
    }

    /** Run a statement and return every row. */
    public List<Row> query(String sql, Object... params) {
        try (PreparedStatement statement = prepare(sql, params);
                ResultSet found = statement.executeQuery()) {
            ResultSetMetaData meta = found.getMetaData();
            int count = meta.getColumnCount();
            List<Row> rows = new ArrayList<>();
            while (found.next()) {
                Map<String, Object> columns = new LinkedHashMap<>();
                List<Object> values = new ArrayList<>(count);
                for (int index = 1; index <= count; index++) {
                    Object value = normalize(found.getObject(index));
                    columns.putIfAbsent(meta.getColumnLabel(index), value);
                    values.add(value);
                }
                rows.add(new Row(columns, values));
            }
            return rows;
        } catch (SQLException exc) {
            throw SqlFailure.of(exc);
        }
    }

    /** Run a statement and return its first row, if it has one. */
    public Optional<Row> queryOne(String sql, Object... params) {
        List<Row> rows = query(sql, params);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    /** Run a statement that returns no rows. Returns how many rows it changed. */
    public int execute(String sql, Object... params) {
        try (PreparedStatement statement = prepare(sql, params)) {
            if (statement.execute()) {
                return 0;
            }
            return Math.max(statement.getUpdateCount(), 0);
        } catch (SQLException exc) {
            throw SqlFailure.of(exc);
        }
    }

    /** Run one statement once for every row of parameters. */
    public void executeMany(String sql, List<List<Object>> rows) {
        if (rows.isEmpty()) {
            return;
        }
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (List<Object> row : rows) {
                bind(statement, row.toArray());
                statement.addBatch();
            }
            statement.executeBatch();
        } catch (SQLException exc) {
            throw SqlFailure.of(exc);
        }
    }

    /** Rows changed since the connection opened, as SQLite counts them. */
    public long totalChanges() {
        return ((Number) queryOne("SELECT total_changes()").orElseThrow().get(0)).longValue();
    }

    /** Run {@code work} inside one transaction, committing if it returns. */
    public <T> T transaction(Supplier<T> work) {
        if (inTransaction) {
            return work.get();
        }
        transactions++;
        try {
            connection.setAutoCommit(false);
        } catch (SQLException exc) {
            throw SqlFailure.of(exc);
        }
        inTransaction = true;
        boolean committed = false;
        try {
            T result = work.get();
            connection.commit();
            committed = true;
            return result;
        } catch (SQLException exc) {
            throw SqlFailure.of(exc);
        } finally {
            inTransaction = false;
            restore(committed);
        }
    }

    private void restore(boolean committed) {
        try {
            if (!committed) {
                connection.rollback();
            }
            connection.setAutoCommit(true);
        } catch (SQLException exc) {
            throw SqlFailure.of(exc);
        }
    }

    /** Run {@code work} inside one transaction. */
    public void transaction(Runnable work) {
        transaction(() -> {
            work.run();
            return null;
        });
    }

    /** Whether the connection has been closed. */
    public boolean isClosed() {
        try {
            return connection.isClosed();
        } catch (SQLException exc) {
            throw SqlFailure.of(exc);
        }
    }

    @Override
    public void close() {
        try {
            connection.close();
        } catch (SQLException exc) {
            throw SqlFailure.of(exc);
        }
    }

    private PreparedStatement prepare(String sql, Object... params) throws SQLException {
        if (connection.isClosed()) {
            throw new SQLException("Cannot operate on a closed database.");
        }
        PreparedStatement statement = connection.prepareStatement(sql);
        bind(statement, params);
        return statement;
    }

    private static void bind(PreparedStatement statement, Object[] params) throws SQLException {
        for (int index = 0; index < params.length; index++) {
            statement.setObject(index + 1, params[index]);
        }
    }

    private static Object normalize(Object value) {
        if (value instanceof Integer || value instanceof Short || value instanceof Byte) {
            return ((Number) value).longValue();
        }
        if (value instanceof Float found) {
            return found.doubleValue();
        }
        return value;
    }
}
