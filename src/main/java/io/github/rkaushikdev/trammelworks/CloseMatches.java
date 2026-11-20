package io.github.rkaushikdev.trammelworks;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The nearest known word to a misspelt one, by the same similarity ratio the reference
 * {@code difflib} uses: twice the matched characters over the combined length, with matches found
 * by repeatedly taking the longest common block.
 *
 * <p>The ratio has to be that one exactly, because the cutoff of 0.6 was tuned against it and a
 * different measure suggests different keys.
 */
final class CloseMatches {

    private CloseMatches() {}

    /** The best match scoring at least {@code cutoff}, ties going to the later word. */
    static Optional<String> best(String word, Collection<String> possibilities, double cutoff) {
        String found = null;
        double score = -1;
        int[] b = word.codePoints().toArray();
        Matcher matcher = new Matcher(b);
        for (String candidate : possibilities) {
            int[] a = candidate.codePoints().toArray();
            if (realQuickRatio(a, b) >= cutoff && quickRatio(a, b) >= cutoff) {
                double ratio = matcher.ratio(a);
                if (ratio >= cutoff
                        && (ratio > score || (ratio == score && Json.compareCodePoints(candidate, found) > 0))) {
                    score = ratio;
                    found = candidate;
                }
            }
        }
        return Optional.ofNullable(found);
    }

    private static double ratio(int matches, int length) {
        return length == 0 ? 1.0 : 2.0 * matches / length;
    }

    private static double realQuickRatio(int[] a, int[] b) {
        return ratio(Math.min(a.length, b.length), a.length + b.length);
    }

    private static double quickRatio(int[] a, int[] b) {
        Map<Integer, Integer> available = new HashMap<>();
        for (int point : b) {
            available.merge(point, 1, Integer::sum);
        }
        int matches = 0;
        for (int point : a) {
            int left = available.getOrDefault(point, 0);
            available.put(point, left - 1);
            if (left > 0) {
                matches++;
            }
        }
        return ratio(matches, a.length + b.length);
    }

    /** Longest-block matching against one fixed second sequence. */
    private static final class Matcher {
        private final int[] b;
        private final Map<Integer, List<Integer>> b2j = new HashMap<>();

        Matcher(int[] b) {
            this.b = b;
            for (int j = 0; j < b.length; j++) {
                b2j.computeIfAbsent(b[j], key -> new ArrayList<>()).add(j);
            }
            if (b.length >= 200) {
                int popular = b.length / 100 + 1;
                Set<Integer> drop = new HashSet<>();
                b2j.forEach((point, indexes) -> {
                    if (indexes.size() > popular) {
                        drop.add(point);
                    }
                });
                drop.forEach(b2j::remove);
            }
        }

        double ratio(int[] a) {
            int matches = 0;
            Deque<int[]> queue = new ArrayDeque<>();
            queue.push(new int[] {0, a.length, 0, b.length});
            while (!queue.isEmpty()) {
                int[] range = queue.pop();
                int[] found = longest(a, range[0], range[1], range[2], range[3]);
                int i = found[0];
                int j = found[1];
                int k = found[2];
                if (k > 0) {
                    matches += k;
                    if (range[0] < i && range[2] < j) {
                        queue.push(new int[] {range[0], i, range[2], j});
                    }
                    if (i + k < range[1] && j + k < range[3]) {
                        queue.push(new int[] {i + k, range[1], j + k, range[3]});
                    }
                }
            }
            return CloseMatches.ratio(matches, a.length + b.length);
        }

        private int[] longest(int[] a, int alo, int ahi, int blo, int bhi) {
            int besti = alo;
            int bestj = blo;
            int bestsize = 0;
            Map<Integer, Integer> j2len = new HashMap<>();
            for (int i = alo; i < ahi; i++) {
                Map<Integer, Integer> next = new HashMap<>();
                for (int j : b2j.getOrDefault(a[i], List.of())) {
                    if (j < blo) {
                        continue;
                    }
                    if (j >= bhi) {
                        break;
                    }
                    int k = j2len.getOrDefault(j - 1, 0) + 1;
                    next.put(j, k);
                    if (k > bestsize) {
                        besti = i - k + 1;
                        bestj = j - k + 1;
                        bestsize = k;
                    }
                }
                j2len = next;
            }
            while (besti > alo && bestj > blo && a[besti - 1] == b[bestj - 1]) {
                besti--;
                bestj--;
                bestsize++;
            }
            while (besti + bestsize < ahi && bestj + bestsize < bhi && a[besti + bestsize] == b[bestj + bestsize]) {
                bestsize++;
            }
            return new int[] {besti, bestj, bestsize};
        }
    }
}
