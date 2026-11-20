package io.github.rkaushikdev.trammelworks.query;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.rkaushikdev.trammelworks.NanoTime;
import io.github.rkaushikdev.trammelworks.Samples;
import io.github.rkaushikdev.trammelworks.Stream;
import io.github.rkaushikdev.trammelworks.archive.StoredRecord;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Random;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Every filter has to read back as the filter that ran.
 *
 * <p>The reports quote the filter they were produced with, and a dispute review that turns on which
 * records a report covered is not the moment to discover that the quoted text parses to a different
 * tree. So the property is that parsing a rendered tree gives the tree back, for every tree the
 * language can express, including the ones nobody would type. Trees are drawn from a fixed seed.
 */
class QueryRoundtripTest {

    static final NanoTime NOW = NanoTime.parse("2025-06-10T12:00:00Z");

    static final List<String> FIELDS = List.of(
            "site", "unit", "class", "stream", "message", "confidence", "label.lane", "label.pump", "label.truncated");

    static final List<Op> TEXT_OPS = List.of(Op.EQ, Op.NE, Op.MATCH, Op.NOT_MATCH, Op.LT, Op.GT);

    /** Printable ASCII without the backslash. */
    static final String ALPHABET = alphabet();

    static String alphabet() {
        StringBuilder out = new StringBuilder();
        for (char character = 32; character <= 126; character++) {
            if (character != '\\') {
                out.append(character);
            }
        }
        return out.toString();
    }

    static String value(Random random) {
        return Samples.text(random, ALPHABET, 1, 12);
    }

    static <T> T pick(Random random, List<T> items) {
        return items.get(random.nextInt(items.size()));
    }

    static Node leaf(Random random) {
        switch (random.nextInt(3)) {
            case 0 -> {
                return new Compare(pick(random, FIELDS), pick(random, TEXT_OPS), value(random));
            }
            case 1 -> {
                List<String> values = new ArrayList<>();
                int count = 1 + random.nextInt(4);
                for (int index = 0; index < count; index++) {
                    values.add(value(random));
                }
                return new InSet(pick(random, FIELDS), values, random.nextBoolean());
            }
            default -> {
                String raw = new NanoTime(random.nextInt(2_000_000_001) * 1_000_000_000L).isoformat();
                long atNs = NanoTime.parse(raw).epochNs();
                return random.nextBoolean() ? new Since(atNs, raw) : new Until(atNs, raw);
            }
        }
    }

    /** A tree of at most {@code leaves} leaves. */
    static Node tree(Random random, int leaves) {
        if (leaves <= 1 || random.nextInt(3) == 0) {
            return leaf(random);
        }
        return switch (random.nextInt(3)) {
            case 0 -> {
                int left = 1 + random.nextInt(leaves - 1);
                yield new And(tree(random, left), tree(random, leaves - left));
            }
            case 1 -> {
                int left = 1 + random.nextInt(leaves - 1);
                yield new Or(tree(random, left), tree(random, leaves - left));
            }
            default -> new Not(tree(random, leaves));
        };
    }

    static java.util.stream.Stream<Node> trees() {
        return Samples.drawn(0x7EE5, 200, random -> tree(random, 6));
    }

    static java.util.stream.Stream<Object[]> treesWithLimits() {
        return Samples.drawn(0x11A1, 100, random -> new Object[] {tree(random, 6), 1 + random.nextInt(5000)});
    }

    static java.util.stream.Stream<String> values() {
        return Samples.drawn(0xBA1E, 200, QueryRoundtripTest::value);
    }

    static Query parse(String text) {
        return Parser.parseQuery(text, NOW);
    }

    @ParameterizedTest
    @MethodSource("trees")
    void aTreeSurvivesBeingWrittenOutAndReadBack(Node node) {
        assertThat(parse(node.render()).where()).contains(node);
    }

    @ParameterizedTest
    @MethodSource("trees")
    void renderingIsStableAcrossARoundTrip(Node node) {
        String once = node.render();
        Node twice = parse(once).where().orElseThrow();
        assertThat(twice.render()).isEqualTo(once);
    }

    @ParameterizedTest
    @MethodSource("treesWithLimits")
    void aLimitSurvivesToo(Node node, int limit) {
        Query original = new Query(Optional.of(node), OptionalInt.of(limit));
        assertThat(parse(original.render())).isEqualTo(original);
    }

    @ParameterizedTest
    @MethodSource("trees")
    void everyTreeCanBePlanned(Node node) {
        assertThat(Plan.plan(Query.of(node)).sql()).startsWith("SELECT");
    }

    @ParameterizedTest
    @MethodSource("trees")
    void aPlanBindsOneValuePerHole(Node node) {
        Plan built = Plan.plan(Query.of(node));
        assertThat(built.whereSql()
                        .chars()
                        .filter(character -> character == '?')
                        .count())
                .isEqualTo(built.params().size());
    }

    @ParameterizedTest
    @MethodSource("trees")
    void aTreeThatTouchesALabelIsNeverFullyPushed(Node node) {
        Plan built = Plan.plan(Query.of(node));
        boolean touchesLabel = Ast.fieldsUsed(node).stream().anyMatch(name -> name.startsWith("label."));
        assertThat(built.fullyPushed()).isEqualTo(!touchesLabel);
    }

    @ParameterizedTest
    @MethodSource("trees")
    void aResidualOnlyAppearsWhereSomethingCouldNotBePushed(Node node) {
        assertThat(Plan.plan(Query.of(node)).fullyPushed()).isEqualTo(Plan.isPushable(node));
    }

    @ParameterizedTest
    @MethodSource("trees")
    void walkingATreeReachesEveryNode(Node node) {
        List<Node> found = Ast.walk(node);
        assertThat(found).contains(node).allSatisfy(item -> assertThat(item).isInstanceOf(Node.class));
    }

    @ParameterizedTest
    @MethodSource("trees")
    void theEvaluatorAnswersWithoutRaising(Node node) {
        StoredRecord row = new StoredRecord(
                "k",
                "US-TX-0417",
                "pump-bridge",
                "standard",
                Stream.STDOUT,
                NOW,
                Optional.empty(),
                "measured",
                "nozzle 1 lifted",
                Map.of("lane", "2"),
                NOW);
        assertThat(Evaluate.matches(node, row)).isIn(true, false);
    }

    @ParameterizedTest
    @MethodSource("values")
    void anyValueSurvivesQuoting(String value) {
        Compare node = new Compare("message", Op.EQ, value);
        assertThat(parse(node.render()).where()).contains(node);
    }
}
