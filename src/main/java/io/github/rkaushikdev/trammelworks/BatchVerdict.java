package io.github.rkaushikdev.trammelworks;

import java.nio.file.Path;

/**
 * What one batch turned out to be.
 *
 * @param path the batch file
 * @param ok whether it may be taken as genuine
 * @param site the site it claims to come from, when it could be read
 * @param batchId the id it claims, when it could be read
 * @param records how many records it carries, when accepted
 * @param reason why it was refused
 */
public record BatchVerdict(Path path, boolean ok, String site, String batchId, int records, String reason) {

    static BatchVerdict refused(Path path, String site, String batchId, String reason) {
        return new BatchVerdict(path, false, site, batchId, 0, reason);
    }

    static BatchVerdict accepted(Path path, String site, String batchId, int records) {
        return new BatchVerdict(path, true, site, batchId, records, "");
    }

    @Override
    public String toString() {
        String name = path.getFileName().toString();
        return ok ? name + " ok, " + records + " records from " + site : name + " REFUSED: " + reason;
    }
}
