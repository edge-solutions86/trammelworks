package io.github.rkaushikdev.trammelworks.clockfix;

import io.github.rkaushikdev.trammelworks.ConfigError;
import io.github.rkaushikdev.trammelworks.LogRecord;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Watches the record stream for time daemon lines and files what it finds in the ledger. */
public final class SyncObserver {

    private final SessionLedger ledger;
    private final String defaultBootId;
    private final Set<String> units;
    private int seen;
    private int filed;
    private int ignoredNoPosition;

    /** An observer watching chronyd and timesyncd. */
    public SyncObserver(SessionLedger ledger, String defaultBootId) {
        this.ledger = ledger;
        this.defaultBootId = defaultBootId;
        Set<String> watched = new HashSet<>(SyncLines.CHRONY_UNITS);
        watched.addAll(SyncLines.TIMESYNCD_UNITS);
        this.units = Set.copyOf(watched);
    }

    /** The ledger syncs are filed in. */
    public SessionLedger ledger() {
        return ledger;
    }

    /** Time daemon lines looked at. */
    public int seen() {
        return seen;
    }

    /** Syncs filed. */
    public int filed() {
        return filed;
    }

    /** Sync lines that could not be placed in their session. */
    public int ignoredNoPosition() {
        return ignoredNoPosition;
    }

    /** The session a record belongs to: its own label, or this run's boot. */
    public String bootOf(LogRecord record) {
        String stated = record.labels().get(SyncLines.BOOT_LABEL);
        return stated == null || stated.isEmpty() ? defaultBootId : stated;
    }

    /** Inspect one record. Files the event when there is one. */
    public Optional<Observation> look(LogRecord record) {
        if (!units.contains(record.unit())) {
            return Optional.empty();
        }
        seen++;
        String bootId = bootOf(record);
        Optional<Observation> found;
        try {
            found = SyncLines.CHRONY_UNITS.contains(record.unit())
                    ? SyncLines.observeChrony(record, bootId)
                    : SyncLines.observeTimesyncd(record, bootId);
        } catch (ConfigError exc) {
            return Optional.empty();
        }
        if (found.isEmpty()) {
            if (SyncLines.position(record).isEmpty() && SyncLines.looksLikeASync(record.message())) {
                ignoredNoPosition++;
            }
            return Optional.empty();
        }
        ledger.addSync(found.get().event());
        filed++;
        return found;
    }

    /** Inspect a batch. Returns only the hits. */
    public List<Observation> lookAll(List<LogRecord> records) {
        List<Observation> out = new ArrayList<>();
        for (LogRecord record : records) {
            look(record).ifPresent(out::add);
        }
        return out;
    }
}
