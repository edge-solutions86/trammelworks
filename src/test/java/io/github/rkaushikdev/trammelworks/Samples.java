package io.github.rkaushikdev.trammelworks;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * Deterministic example generation for the property tests.
 *
 * <p>Each property runs over its boundary values plus a fixed-seed spread of examples, so a failure
 * names the same input on every run and on every machine.
 */
public final class Samples {

    /** How many generated examples a property sees, besides its boundaries. */
    public static final int EXAMPLES = 100;

    private Samples() {}

    /** A seeded generator, so every property draws the same examples on every run. */
    public static Random random(long seed) {
        return new Random(seed);
    }

    /** Integers spread across {@code [min, max]}, both ends included. */
    public static Stream<Long> longs(long min, long max) {
        Random random = random(min ^ max ^ 0x5DEECE66DL);
        List<Long> out = new ArrayList<>();
        out.add(min);
        out.add(max);
        if (max > min) {
            out.add(min + 1);
            out.add(max - 1);
        }
        for (int index = 0; index < EXAMPLES; index++) {
            long span = max - min;
            long offset = span == 0 ? 0 : Math.floorMod(random.nextLong(), span + 1);
            if (index % 3 == 0 && span > 1000) {
                // Small values are where the interesting boundaries live, so weight towards them.
                offset = random.nextInt(1000);
            }
            out.add(min + offset);
        }
        return out.stream();
    }

    /** {@code EXAMPLES} values built from a seeded generator. */
    public static <T> Stream<T> drawn(long seed, Function<Random, T> draw) {
        Random random = random(seed);
        List<T> out = new ArrayList<>();
        for (int index = 0; index < EXAMPLES; index++) {
            out.add(draw.apply(random));
        }
        return out.stream();
    }

    /** A string of {@code length} characters drawn from {@code alphabet}. */
    public static String text(Random random, String alphabet, int minLength, int maxLength) {
        int length = minLength + random.nextInt(maxLength - minLength + 1);
        StringBuilder out = new StringBuilder();
        int[] points = alphabet.codePoints().toArray();
        for (int index = 0; index < length; index++) {
            out.appendCodePoint(points[random.nextInt(points.length)]);
        }
        return out.toString();
    }
}
