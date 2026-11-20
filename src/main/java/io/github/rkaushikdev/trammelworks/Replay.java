package io.github.rkaushikdev.trammelworks;

import io.github.rkaushikdev.trammelworks.ship.Check;
import io.github.rkaushikdev.trammelworks.ship.Signature;
import io.github.rkaushikdev.trammelworks.ship.Signing;
import io.github.rkaushikdev.trammelworks.ship.SigningKey;
import io.github.rkaushikdev.trammelworks.ship.Transport;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Reading a directory of batches back, the way the estate does off a stick.
 *
 * <p>A site whose modem has been dead for a week gets visited: the file transport's output
 * directory is copied onto a stick and the stick goes back to the office. This reads that pile. It
 * is not the shipper in reverse: it never sends anything, never touches a spool, and answers one
 * question per file, which is whether this batch is what the site that claims to have written it
 * actually wrote.
 *
 * <p>Every failure is reported rather than raised, because some of a stick will be wrong and a
 * reader that stopped at the first bad file would need an engineer to delete files one at a time.
 * The order is by batch id rather than by file name or modification time, which copying a directory
 * does not preserve reliably.
 */
public final class Replay {

    /** What a batch file's name ends with. */
    public static final String BATCH_SUFFIX = ".json";

    private Replay() {}

    /** Every batch file in a directory, sorted by name, with its signature if one is beside it. */
    public static List<BatchFile> findBatches(Path directory) {
        if (!Files.isDirectory(directory)) {
            throw new ShipError("not a directory of batches", directory.toString());
        }
        List<Path> entries;
        try (Stream<Path> listed = Files.list(directory)) {
            entries = new ArrayList<>(listed.toList());
        } catch (IOException exc) {
            throw new UncheckedIOException(exc);
        }
        entries.sort((left, right) -> Json.compareCodePoints(
                left.getFileName().toString(), right.getFileName().toString()));
        List<BatchFile> found = new ArrayList<>();
        for (Path path : entries) {
            if (!Files.isRegularFile(path)
                    || !suffix(path.getFileName().toString()).equals(BATCH_SUFFIX)) {
                continue;
            }
            Path beside = path.resolveSibling(path.getFileName() + Transport.SIGNATURE_SUFFIX);
            found.add(new BatchFile(path, Files.isRegularFile(beside) ? Optional.of(beside) : Optional.empty()));
        }
        return found;
    }

    /** The last dotted part of a name, empty for a name that only starts with a dot. */
    private static String suffix(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 && dot < name.length() - 1 ? name.substring(dot) : "";
    }

    /** The batch body, checked to be the shape a batch has. */
    public static Map<String, Object> readPayload(Path path) {
        byte[] raw;
        try {
            raw = Files.readAllBytes(path);
        } catch (IOException exc) {
            throw new ShipError("batch file unreadable", JsonFiles.describe(exc));
        }
        Object payload;
        try {
            String text = StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(raw))
                    .toString();
            payload = Json.loads(text);
        } catch (CharacterCodingException | Json.JsonException exc) {
            throw new ShipError("batch file is not json", path.toString());
        }
        if (!(payload instanceof Map<?, ?>)) {
            throw new ShipError("batch file is not an object", path.toString());
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) payload;
        for (String name : List.of("site", "batch_id", "records")) {
            if (!body.containsKey(name)) {
                throw new ShipError("batch file has no " + name, path.toString());
            }
        }
        if (!(body.get("records") instanceof List<?>)) {
            throw new ShipError("batch records are not a list", path.toString());
        }
        return body;
    }

    /** Decide whether one batch file may be taken as genuine, requiring a signature. */
    public static BatchVerdict checkOne(BatchFile found, SigningKey key, NanoTime now) {
        return checkOne(found, key, now, true);
    }

    /**
     * Decide whether one batch file may be taken as genuine. With no key there is nothing to check
     * against, and unless the caller deliberately accepts unsigned batches that is a refusal rather
     * than a pass: a collector that treats unverifiable as verified has no signatures.
     */
    public static BatchVerdict checkOne(BatchFile found, SigningKey key, NanoTime now, boolean requireSignature) {
        Path path = found.path();
        Map<String, Object> payload;
        try {
            payload = readPayload(path);
        } catch (ShipError exc) {
            return BatchVerdict.refused(path, "", "", exc.getMessage());
        }
        String site = Coerce.text(payload.get("site"));
        String batchId = Coerce.text(payload.get("batch_id"));
        int count = ((List<?>) payload.get("records")).size();
        if (key == null) {
            if (requireSignature) {
                return BatchVerdict.refused(path, site, batchId, "no key to check the signature against");
            }
            return BatchVerdict.accepted(path, site, batchId, count);
        }
        if (found.signaturePath().isEmpty()) {
            return BatchVerdict.refused(path, site, batchId, "no signature beside it");
        }
        Signature signature;
        byte[] body;
        try {
            signature = Signing.parse(Files.readString(found.signaturePath().get(), StandardCharsets.UTF_8));
            body = Files.readAllBytes(path);
        } catch (ShipError exc) {
            return BatchVerdict.refused(path, site, batchId, exc.getMessage());
        } catch (IOException exc) {
            return BatchVerdict.refused(path, site, batchId, JsonFiles.describe(exc));
        }
        Check check = Signing.verify(key, signature, site, batchId, body, now);
        if (!check.ok()) {
            return BatchVerdict.refused(path, site, batchId, check.reason());
        }
        return BatchVerdict.accepted(path, site, batchId, count);
    }

    /** Walk a directory of batches and say what each one is, requiring signatures. */
    public static ReplayReport replay(Path directory, SigningKey key, NanoTime now) {
        return replay(directory, key, now, true);
    }

    /** Walk a directory of batches and say what each one is. */
    public static ReplayReport replay(Path directory, SigningKey key, NanoTime now, boolean requireSignature) {
        ReplayReport report = new ReplayReport();
        for (BatchFile found : findBatches(directory)) {
            report.add(checkOne(found, key, now, requireSignature));
        }
        report.sort();
        return report;
    }

    /**
     * Every record in a set of batch files, in file then record order. Duplicates are left in:
     * delivery is at least once, and deciding what is a duplicate is the collector's job, done on the
     * batch id it already has.
     */
    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> recordsOf(List<Path> paths) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Path path : paths) {
            for (Object item : (List<?>) readPayload(path).get("records")) {
                if (item instanceof Map<?, ?>) {
                    out.add((Map<String, Object>) item);
                }
            }
        }
        return out;
    }
}
