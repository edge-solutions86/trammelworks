package io.github.rkaushikdev.trammelworks;

import java.math.BigInteger;
import java.util.List;

/**
 * A seeded Mersenne Twister (MT19937) with the sampling methods the simulator uses.
 *
 * <p>The simulated corpora are compared across machines and across releases: a failure found with
 * seed 101 has to be the same failure when somebody else runs seed 101. So the generator, the
 * integer seeding, and every sampling method are fixed algorithms rather than whatever the platform
 * generator happens to be: 53-bit floats from two 32-bit draws, integers below a bound by rejection
 * on the bound's bit length, weighted choice by bisecting cumulative weights.
 */
public final class SeededRandom {

    private static final int N = 624;
    private static final int M = 397;
    private static final int MATRIX_A = 0x9908b0df;
    private static final int UPPER_MASK = 0x80000000;
    private static final int LOWER_MASK = 0x7fffffff;

    private final int[] state = new int[N];
    private int index = N + 1;

    /** A generator seeded with {@code seed}; the sign of the seed is ignored. */
    public SeededRandom(long seed) {
        BigInteger value = BigInteger.valueOf(seed).abs();
        int words = Math.max(1, (value.bitLength() + 31) / 32);
        int[] key = new int[words];
        for (int word = 0; word < words; word++) {
            key[word] = value.shiftRight(32 * word).intValue();
        }
        initByArray(key);
    }

    private void initGenrand(int seed) {
        state[0] = seed;
        for (int i = 1; i < N; i++) {
            state[i] = 1812433253 * (state[i - 1] ^ (state[i - 1] >>> 30)) + i;
        }
        index = N;
    }

    private void initByArray(int[] key) {
        initGenrand(19650218);
        int i = 1;
        int j = 0;
        for (int k = Math.max(N, key.length); k > 0; k--) {
            state[i] = (state[i] ^ ((state[i - 1] ^ (state[i - 1] >>> 30)) * 1664525)) + key[j] + j;
            i++;
            j++;
            if (i >= N) {
                state[0] = state[N - 1];
                i = 1;
            }
            if (j >= key.length) {
                j = 0;
            }
        }
        for (int k = N - 1; k > 0; k--) {
            state[i] = (state[i] ^ ((state[i - 1] ^ (state[i - 1] >>> 30)) * 1566083941)) - i;
            i++;
            if (i >= N) {
                state[0] = state[N - 1];
                i = 1;
            }
        }
        state[0] = UPPER_MASK;
    }

    private int nextWord() {
        if (index >= N) {
            int kk = 0;
            for (; kk < N - M; kk++) {
                int y = (state[kk] & UPPER_MASK) | (state[kk + 1] & LOWER_MASK);
                state[kk] = state[kk + M] ^ (y >>> 1) ^ ((y & 1) != 0 ? MATRIX_A : 0);
            }
            for (; kk < N - 1; kk++) {
                int y = (state[kk] & UPPER_MASK) | (state[kk + 1] & LOWER_MASK);
                state[kk] = state[kk + (M - N)] ^ (y >>> 1) ^ ((y & 1) != 0 ? MATRIX_A : 0);
            }
            int y = (state[N - 1] & UPPER_MASK) | (state[0] & LOWER_MASK);
            state[N - 1] = state[M - 1] ^ (y >>> 1) ^ ((y & 1) != 0 ? MATRIX_A : 0);
            index = 0;
        }
        int y = state[index++];
        y ^= y >>> 11;
        y ^= (y << 7) & 0x9d2c5680;
        y ^= (y << 15) & 0xefc60000;
        y ^= y >>> 18;
        return y;
    }

    /** A float in {@code [0, 1)} with 53 random bits. */
    public double random() {
        long a = (nextWord() & 0xffffffffL) >>> 5;
        long b = (nextWord() & 0xffffffffL) >>> 6;
        return (a * 67108864.0 + b) * (1.0 / 9007199254740992.0);
    }

    /** A non-negative integer of {@code bits} random bits, low words drawn first. */
    public long getrandbits(int bits) {
        if (bits <= 0 || bits > 63) {
            throw new IllegalArgumentException("bits must be between 1 and 63");
        }
        if (bits <= 32) {
            return (nextWord() & 0xffffffffL) >>> (32 - bits);
        }
        long low = nextWord() & 0xffffffffL;
        long high = (nextWord() & 0xffffffffL) >>> (64 - bits);
        return (high << 32) | low;
    }

    /** An integer in {@code [0, bound)}, by rejection on the bound's bit length. */
    public long below(long bound) {
        if (bound <= 0) {
            throw new IllegalArgumentException("bound must be positive");
        }
        int bits = 64 - Long.numberOfLeadingZeros(bound);
        long found = getrandbits(bits);
        while (found >= bound) {
            found = getrandbits(bits);
        }
        return found;
    }

    /** An integer in {@code [low, high]}, both ends included. */
    public long randint(long low, long high) {
        if (high < low) {
            throw new IllegalArgumentException("empty range");
        }
        return low + below(high - low + 1);
    }

    /** One item, every item equally likely. */
    public <T> T choice(List<T> items) {
        return items.get((int) below(items.size()));
    }

    /** One item, each as likely as its weight. */
    public <T> T choice(List<T> items, List<Integer> weights) {
        double[] cumulative = new double[weights.size()];
        double total = 0;
        for (int position = 0; position < weights.size(); position++) {
            total += weights.get(position);
            cumulative[position] = total;
        }
        double target = random() * total;
        int lowIndex = 0;
        int highIndex = items.size() - 1;
        while (lowIndex < highIndex) {
            int middle = (lowIndex + highIndex) >>> 1;
            if (target < cumulative[middle]) {
                highIndex = middle;
            } else {
                lowIndex = middle + 1;
            }
        }
        return items.get(lowIndex);
    }

    /** An exponentially distributed float with rate {@code lambda}. */
    public double expovariate(double lambda) {
        return -StrictMath.log(1.0 - random()) / lambda;
    }
}
