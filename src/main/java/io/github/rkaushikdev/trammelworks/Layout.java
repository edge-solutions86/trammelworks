package io.github.rkaushikdev.trammelworks;

/** Which of the three on disk conventions a container log file follows. */
public enum Layout implements Json.Valued {
    /** {@code /var/log/pods/<namespace>_<pod>_<uid>/<container>/<attempt>.log}. */
    CRI("cri"),
    /** {@code /var/log/containers/<pod>_<namespace>_<container>-<containerid>.log}. */
    FLAT("flat"),
    /** {@code /var/lib/docker/containers/<containerid>/<containerid>-json.log}. */
    DOCKER("docker");

    private final String value;

    Layout(String value) {
        this.value = value;
    }

    @Override
    public String value() {
        return value;
    }

    @Override
    public String toString() {
        return value;
    }
}
