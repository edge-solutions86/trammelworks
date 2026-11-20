package io.github.rkaushikdev.trammelworks;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** The whitespace rule a hand edited config is trimmed by, and the letter rule a label key obeys. */
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

    @ParameterizedTest
    @ValueSource(
            strings = {"brand", "lane3", "r\u00e9gion", "x\u00b2", "\u216b", "\u00bd", "\u0663", "\u01c5", "\u02b0"})
    void lettersAndNumbersOfEveryKindAreAlphanumeric(String text) {
        assertThat(Text.isAlnum(text)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "a b", "a.b", "a\u200bb", "brand_code", "region-id"})
    void anythingElseIsNot(String text) {
        assertThat(Text.isAlnum(text)).isFalse();
    }
}
