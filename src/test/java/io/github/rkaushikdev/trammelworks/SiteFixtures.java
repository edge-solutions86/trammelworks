package io.github.rkaushikdev.trammelworks;

import io.github.rkaushikdev.trammelworks.archive.ArchiveSettings;
import io.github.rkaushikdev.trammelworks.ship.Batch;
import io.github.rkaushikdev.trammelworks.ship.MemoryTransport;
import io.github.rkaushikdev.trammelworks.ship.ShipSettings;
import io.github.rkaushikdev.trammelworks.spool.SpoolSettings;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * What the site level tests share: a config whose every section names a path under the test's
 * temporary directory, a site built on a manual clock, and the readings tests take off a transport
 * and a spool directory.
 *
 * <p>Every section is named rather than left to its default, because the defaults are real absolute
 * paths where a commissioned box keeps its state, and a test that leaves one writes to the machine
 * running the suite.
 */
public final class SiteFixtures {

    /** The site every fixture belongs to. */
    public static final String SITE = "US-TX-0417";

    private SiteFixtures() {}

    /** A spool under {@code tmp}, a megabyte with small segments. */
    public static SpoolSettings.Builder spool(Path tmp) {
        return SpoolSettings.builder()
                .directory(PosixPath.asPosix(tmp.resolve("spool")))
                .budgetBytes(1_000_000)
                .segmentBytes(4096);
    }

    /** An archive under {@code tmp}. */
    public static ArchiveSettings.Builder archive(Path tmp) {
        return ArchiveSettings.builder()
                .path(PosixPath.asPosix(tmp.resolve("archive").resolve("records.sqlite3")));
    }

    /** A config reading {@code tree}, with every path under {@code tmp}. */
    public static SiteConfig.Builder config(SiteTree tree, Path tmp) {
        return SiteConfig.builder(SITE)
                .ingest(IngestConfig.at(PosixPath.asPosix(tree.root())))
                .spool(spool(tmp).build())
                .ship(ShipSettings.builder().monthlyAllowanceBytes(10_000_000).build())
                .archive(archive(tmp).build());
    }

    /** A clock stopped at noon on the fixture day. */
    public static ManualClock noon() {
        ManualClock clock = new ManualClock();
        clock.setWall("2025-03-04T12:00:00Z");
        return clock;
    }

    /** A site for {@code config} on a stopped clock. */
    public static Site site(SiteConfig config, MemoryTransport transport) {
        return Site.build(config, transport, noon(), null, new Registry());
    }

    /** Every message the transport received, in order. */
    public static List<String> delivered(MemoryTransport transport) {
        List<String> out = new ArrayList<>();
        for (Batch batch : transport.received()) {
            batch.records().forEach(item -> out.add((String) item.get("message")));
        }
        return out;
    }

    /** The spool segment files in a directory, sorted by name. */
    public static List<Path> segments(Path directory) {
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        try (Stream<Path> found = Files.list(directory)) {
            return found.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".spool"))
                    .sorted()
                    .toList();
        } catch (IOException exc) {
            throw new UncheckedIOException(exc);
        }
    }
}
