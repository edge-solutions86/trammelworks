package io.github.rkaushikdev.trammelworks.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CliRedactTest {

    static final String VISA = "4111111111111111";
    static final String BARE2 = "4111111111111111=270310112345";

    static final String SITE_YAML =
            """

            site: US-TX-0417
            redact:
              rules:
                driver_id:
                  pattern: driver=(\\d{4,10})
            """;

    @TempDir
    Path tmp;

    Path config;

    @BeforeEach
    void setUp() {
        config = CliFixtures.writeConfig(tmp, SITE_YAML);
    }

    Path sample(String... lines) throws IOException {
        Path target = tmp.resolve("sample.log");
        Files.writeString(target, String.join("\n", lines) + "\n", StandardCharsets.UTF_8);
        return target;
    }

    static CliRunner.Result invoke(Path config, String stdin, String... args) {
        String[] full = new String[args.length + 3];
        full[0] = "--config";
        full[1] = config.toString();
        full[2] = "redact";
        System.arraycopy(args, 0, full, 3, args.length);
        return CliRunner.invokeWithInput(stdin, full);
    }

    CliRunner.Result invoke(String... args) {
        return invoke(config, "", args);
    }

    @Test
    void aCleanFileExitsZero() throws IOException {
        assertThat(invoke(sample("nozzle 1 lifted").toString()).exitCode()).isEqualTo(RedactCommand.EXIT_CLEAN);
    }

    @Test
    void aCleanFileIsPrintedBack() throws IOException {
        assertThat(invoke(sample("nozzle 1 lifted").toString()).output()).contains("nozzle 1 lifted");
    }

    @Test
    void aFileWithAPanExitsThree() throws IOException {
        assertThat(invoke(sample("pan=" + VISA).toString()).exitCode()).isEqualTo(RedactCommand.EXIT_FOUND);
    }

    @Test
    void thePanIsMaskedInTheOutput() throws IOException {
        assertThat(invoke(sample("pan=" + VISA).toString()).output())
                .contains("411111******1111")
                .doesNotContain(VISA);
    }

    @Test
    void aStripeIsRemoved() throws IOException {
        assertThat(invoke(sample("swipe " + BARE2).toString()).output())
                .contains("[redacted]")
                .doesNotContain("2703");
    }

    @Test
    void aSiteRuleFires() throws IOException {
        CliRunner.Result result = invoke(sample("auth driver=88213").toString());
        assertThat(result.output()).contains("driver=[redacted]");
        assertThat(result.exitCode()).isEqualTo(RedactCommand.EXIT_FOUND);
    }

    @Test
    void theTallyNamesEachRule() throws IOException {
        assertThat(invoke(sample("pan=" + VISA + " driver=88213").toString()).output())
                .contains("card_pan")
                .contains("driver_id");
    }

    @Test
    void theTallyCountsTheLines() throws IOException {
        assertThat(invoke(sample("one", "two", "three").toString()).output()).contains("3 lines, 0 removed");
    }

    @Test
    void quietPrintsOnlyTheTally() throws IOException {
        CliRunner.Result result = invoke("--quiet", sample("nozzle 1 lifted").toString());
        assertThat(result.output()).doesNotContain("nozzle 1 lifted").contains("1 lines");
    }

    @Test
    void explainNamesTheRulesThatFired() throws IOException {
        assertThat(invoke("--explain", sample("pan=" + VISA).toString()).output())
                .contains("line 1: card_pan");
    }

    @Test
    void explainNeverPrintsWhatWasRemoved() throws IOException {
        assertThat(invoke("--explain", sample("pan=" + VISA).toString()).output())
                .doesNotContain(VISA);
    }

    @Test
    void explainSaysNothingAboutACleanLine() throws IOException {
        assertThat(invoke("--explain", sample("clean").toString()).output()).doesNotContain("line 1");
    }

    @Test
    void inputCanComeFromStdin() {
        CliRunner.Result result = invoke(config, "pan=" + VISA + "\n");
        assertThat(result.output()).contains("411111******1111");
        assertThat(result.exitCode()).isEqualTo(RedactCommand.EXIT_FOUND);
    }

    @Test
    void anEmptyStdinIsClean() {
        CliRunner.Result result = invoke(config, "");
        assertThat(result.exitCode()).isEqualTo(RedactCommand.EXIT_CLEAN);
        assertThat(result.output()).contains("0 lines");
    }

    @Test
    void aMissingInputFileFails() {
        assertThat(invoke(tmp.resolve("absent.log").toString()).exitCode())
                .isNotIn(RedactCommand.EXIT_CLEAN, RedactCommand.EXIT_FOUND);
    }

    @Test
    void aMissingConfigFails() {
        assertThat(CliRunner.invoke("--config", tmp.resolve("nope.yaml").toString(), "redact")
                        .output())
                .contains("stanchion.config");
    }

    @Test
    void aDisabledPassIsAnnounced() {
        Path target = CliFixtures.writeConfig(tmp, "site: XX-LAB-0001\nredact:\n  enabled: false\n");
        assertThat(invoke(target, "pan=" + VISA + "\n").output()).contains("redaction is disabled");
    }

    @Test
    void invalidUtf8InTheInputDoesNotStopTheRun() throws IOException {
        Path target = tmp.resolve("sample.log");
        Files.write(
                target, new byte[] {'f', 'r', 'a', 'm', 'e', ' ', (byte) 0xff, (byte) 0xfe, ' ', 'e', 'n', 'd', '\n'});
        assertThat(invoke(target.toString()).exitCode()).isEqualTo(RedactCommand.EXIT_CLEAN);
    }

    @Test
    void severalLinesAreEachReported() throws IOException {
        assertThat(invoke("--explain", sample("clean", "pan=" + VISA).toString())
                        .output())
                .contains("line 2: card_pan")
                .doesNotContain("line 1");
    }

    @Test
    void everyLineEndingEndsALine() {
        assertThat(RedactCommand.splitLines("a\r\nb\rc d\n\n")).containsExactly("a", "b", "c", "d", "");
        assertThat(RedactCommand.splitLines("")).isEmpty();
    }
}
