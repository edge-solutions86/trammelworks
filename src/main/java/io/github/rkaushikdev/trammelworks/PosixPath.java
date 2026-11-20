package io.github.rkaushikdev.trammelworks;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * A pure POSIX path: text only, never touching the filesystem.
 *
 * <p>Container log paths are reasoned about as the box spells them, with forward slashes, whatever
 * the machine running the code. Normalization follows the usual pure path rules: repeated slashes
 * collapse, {@code .} components drop, a trailing slash goes, and an empty path is {@code .}.
 */
public final class PosixPath {

    private final String anchor;
    private final List<String> parts;

    private PosixPath(String anchor, List<String> parts) {
        this.anchor = anchor;
        this.parts = List.copyOf(parts);
    }

    /** Parse and normalize. */
    public static PosixPath of(String raw) {
        String anchor = "";
        if (raw.startsWith("/")) {
            anchor = raw.startsWith("//") && !raw.startsWith("///") ? "//" : "/";
        }
        List<String> parts = new ArrayList<>();
        for (String piece : raw.split("/")) {
            if (!piece.isEmpty() && !piece.equals(".")) {
                parts.add(piece);
            }
        }
        return new PosixPath(anchor, parts);
    }

    /** A filesystem path seen with forward slashes. */
    public static PosixPath of(Path path) {
        return of(asPosix(path));
    }

    /** A filesystem path's text with forward slashes. */
    public static String asPosix(Path path) {
        return path.toString().replace(java.io.File.separatorChar, '/');
    }

    /** The final component, or an empty string for a root or {@code .}. */
    public String name() {
        return parts.isEmpty() ? "" : parts.get(parts.size() - 1);
    }

    /** The path without its final component. */
    public PosixPath parent() {
        if (parts.isEmpty()) {
            return this;
        }
        return new PosixPath(anchor, parts.subList(0, parts.size() - 1));
    }

    /** Every ancestor, nearest first, ending at the anchor or {@code .}. */
    public List<PosixPath> parents() {
        List<PosixPath> out = new ArrayList<>();
        PosixPath current = this;
        while (!current.parts.isEmpty()) {
            current = current.parent();
            out.add(current);
        }
        return out;
    }

    /** Whether the path starts at the root. */
    public boolean isAbsolute() {
        return !anchor.isEmpty();
    }

    /** The components after the anchor. */
    public List<String> parts() {
        return parts;
    }

    /** A child path. */
    public PosixPath resolve(String child) {
        PosixPath other = of(child);
        if (other.isAbsolute()) {
            return other;
        }
        List<String> joined = new ArrayList<>(parts);
        joined.addAll(other.parts);
        return new PosixPath(anchor, joined);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof PosixPath path && anchor.equals(path.anchor) && parts.equals(path.parts);
    }

    @Override
    public int hashCode() {
        return anchor.hashCode() * 31 + parts.hashCode();
    }

    @Override
    public String toString() {
        if (parts.isEmpty()) {
            return anchor.isEmpty() ? "." : anchor;
        }
        return anchor + String.join("/", parts);
    }
}
