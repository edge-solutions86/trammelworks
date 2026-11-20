package io.github.rkaushikdev.trammelworks;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** The whitespace rule a hand edited config is trimmed by. */
class TextTest {

    @Test
    void noBreakAndIdeographicSpacesAreTrimmed() {
        assertThat(Text.strip("  US-TX-0417　 \n")).isEqualTo("US-TX-0417");
    }

    @Test
    void theSpacesInsideAreKept() {
        assertThat(Text.strip(" a b ")).isEqualTo("a b");
    }

    @Test
    void anythingThatIsNotSpaceStays() {
        assertThat(Text.strip("​zero width")).isEqualTo("​zero width");
        assertThat(Text.isSpace('x')).isFalse();
        assertThat(Text.isSpace(0x1f)).isTrue();
    }

    @Test
    void aSiteIdPastedFromASpreadsheetStillParses() {
        assertThat(Identity.parseSite(" us-tx-0417 ")).isEqualTo("US-TX-0417");
    }
}
