package io.github.rkaushikdev.trammelworks.archive;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * One result row, readable by column name or by position. Integers come back as {@link Long},
 * reals as {@link Double}, text as {@link String} and NULL as {@code null}.
 *
 * @param columns the values by column label, in select order
 * @param values the values by position
 */
public record Row(Map<String, Object> columns, List<Object> values) {

    /** Keeps the maps as given; rows are built once and only read. */
    public Row {
        columns = Collections.unmodifiableMap(columns);
        values = Collections.unmodifiableList(values);
    }

    /** A value by column name. */
    public Object get(String column) {
        if (!columns.containsKey(column)) {
            throw new IllegalArgumentException("no such column " + column);
        }
        return columns.get(column);
    }

    /** A value by position. */
    public Object get(int index) {
        return values.get(index);
    }

    /** A value by name, as a long; the column must not be NULL. */
    public long getLong(String column) {
        return ((Number) get(column)).longValue();
    }

    /** A value by name, as text. */
    public String getString(String column) {
        Object value = get(column);
        return value == null ? null : value.toString();
    }

    /** Whether a column is NULL. */
    public boolean isNull(String column) {
        return get(column) == null;
    }
}
