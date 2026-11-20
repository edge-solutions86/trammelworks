package io.github.rkaushikdev.trammelworks;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.function.Consumer;

/** Somewhere an {@link EventLog} writes its lines. */
@FunctionalInterface
public interface EventSink {

    /** Write text, which is one line with its terminator. */
    void write(String text);

    /** Push anything buffered to its destination. */
    default void flush() {}

    /** Adapt a plain callable into a sink. */
    static EventSink of(Consumer<String> target) {
        return target::accept;
    }

    /** Adapt anything appendable, such as a writer or a print stream. */
    static EventSink of(Appendable target) {
        return new EventSink() {
            @Override
            public void write(String text) {
                try {
                    target.append(text);
                } catch (IOException exc) {
                    throw new UncheckedIOException(exc);
                }
            }

            @Override
            public void flush() {
                if (target instanceof java.io.Flushable flushable) {
                    try {
                        flushable.flush();
                    } catch (IOException exc) {
                        throw new UncheckedIOException(exc);
                    }
                }
            }
        };
    }
}
