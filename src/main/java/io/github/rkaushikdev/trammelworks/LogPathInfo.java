package io.github.rkaushikdev.trammelworks;

import java.util.Optional;

/**
 * What a path says about the container that wrote it.
 *
 * @param path the normalized path
 * @param layout which convention it follows
 * @param unit the service name, or {@code c<short id>} for docker json-file paths
 * @param attempt the restart counter, which only the CRI layout carries
 * @param containerId the full container id, where the path carries one
 * @param namespace the pod namespace, where the path carries one
 * @param pod the pod name, where the path carries one
 * @param rotation how many rotations old the file is, 0 for the live file
 * @param compressed whether the file is a compressed rotation
 */
public record LogPathInfo(
        String path,
        Layout layout,
        String unit,
        Optional<Integer> attempt,
        Optional<String> containerId,
        Optional<String> namespace,
        Optional<String> pod,
        int rotation,
        boolean compressed) {

    /** True for the file the runtime is writing to right now. */
    public boolean isCurrent() {
        return rotation == 0 && !compressed;
    }
}
