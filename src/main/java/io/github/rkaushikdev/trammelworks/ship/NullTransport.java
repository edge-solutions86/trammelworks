package io.github.rkaushikdev.trammelworks.ship;

/** Accepts everything and keeps nothing. */
public final class NullTransport implements Transport {

    private int sent;
    private long bytesSent;

    /** Batches accepted. */
    public int sent() {
        return sent;
    }

    /** Bytes accepted. */
    public long bytesSent() {
        return bytesSent;
    }

    @Override
    public Response send(Batch batch) {
        int size = batch.wireBytes();
        sent++;
        bytesSent += size;
        return new Response(Outcome.OK, 200).withBytesSent(size);
    }
}
