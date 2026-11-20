package io.github.rkaushikdev.trammelworks.ship;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;

/** Keeps what it was given and answers from a script, for tests and the replay tool. */
public class MemoryTransport implements Transport {

    private final Deque<Response> script = new ArrayDeque<>();
    private final List<Batch> received = new ArrayList<>();
    private final Response fallback;

    /** A transport that accepts everything. */
    public MemoryTransport() {
        this(List.of());
    }

    /** A transport answering from {@code script}, then accepting. */
    public MemoryTransport(List<Response> script) {
        this(script, new Response(Outcome.OK, 200));
    }

    /** A transport answering from {@code script}, then with {@code fallback}. */
    public MemoryTransport(List<Response> script, Response fallback) {
        this.script.addAll(script);
        this.fallback = fallback;
    }

    /** Replace what is left of the script. */
    public void script(List<Response> replacement) {
        script.clear();
        script.addAll(replacement);
    }

    /** Every batch sent, in order. */
    public List<Batch> received() {
        return Collections.unmodifiableList(received);
    }

    /** The ids of every batch sent. */
    public List<String> batchIds() {
        return received.stream().map(Batch::batchId).toList();
    }

    @Override
    public Response send(Batch batch) {
        received.add(batch);
        Response answer = script.isEmpty() ? fallback : script.removeFirst();
        return answer.withBytesSent(batch.wireBytes());
    }
}
