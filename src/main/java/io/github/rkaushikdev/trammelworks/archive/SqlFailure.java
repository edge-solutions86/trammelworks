package io.github.rkaushikdev.trammelworks.archive;

import java.sql.SQLException;
import org.sqlite.SQLiteErrorCode;
import org.sqlite.SQLiteException;

/**
 * A statement the archive refused, sorted into the kinds a caller has to tell apart: a broken
 * constraint, an operation the database will not do right now (read only, locked, no such table),
 * a file that is not a database at all, or a connection already closed.
 */
public final class SqlFailure extends RuntimeException {

    /** What kind of refusal. */
    public enum Kind {
        /** A unique key or other constraint was broken. */
        INTEGRITY,
        /** The database will not do this: read only, busy, malformed SQL, missing table. */
        OPERATIONAL,
        /** The file is not a database, or is damaged. */
        DATABASE,
        /** The connection was already closed. */
        CLOSED
    }

    private final Kind kind;

    SqlFailure(Kind kind, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
    }

    /** What kind of refusal. */
    public Kind kind() {
        return kind;
    }

    static SqlFailure of(SQLException exc) {
        Kind kind = Kind.OPERATIONAL;
        if (exc instanceof SQLiteException sqlite) {
            SQLiteErrorCode code = sqlite.getResultCode();
            int primary = code.code & 0xff;
            if (primary == SQLiteErrorCode.SQLITE_CONSTRAINT.code) {
                kind = Kind.INTEGRITY;
            } else if (primary == SQLiteErrorCode.SQLITE_NOTADB.code
                    || primary == SQLiteErrorCode.SQLITE_CORRUPT.code) {
                kind = Kind.DATABASE;
            }
        } else if (String.valueOf(exc.getMessage()).contains("closed")) {
            kind = Kind.CLOSED;
        }
        return new SqlFailure(kind, String.valueOf(exc.getMessage()), exc);
    }
}
