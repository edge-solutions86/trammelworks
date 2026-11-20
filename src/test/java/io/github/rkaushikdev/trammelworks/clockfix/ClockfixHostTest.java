package io.github.rkaushikdev.trammelworks.clockfix;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ClockfixHostTest {

    static final String KERNEL_FORM = "4c1f2e3d-4c1f-2e3d-4c1f-2e3d4c1f2e3d";
    static final String FLAT = "4c1f2e3d4c1f2e3d4c1f2e3d4c1f2e3d";

    @TempDir
    Path tmp;

    Path procFile(String text) {
        try {
            return Files.writeString(tmp.resolve("boot_id"), text);
        } catch (IOException exc) {
            throw new UncheckedIOException(exc);
        }
    }

    Path state() {
        return tmp.resolve("state");
    }

    Path absent() {
        return tmp.resolve("absent");
    }

    @Test
    void theKernelFormWithDashesIsRead() {
        assertThat(BootIdentity.readKernelBootId(procFile(KERNEL_FORM + "\n"))).contains(FLAT);
    }

    @Test
    void aFlatKernelValueIsRead() {
        assertThat(BootIdentity.readKernelBootId(procFile(FLAT))).contains(FLAT);
    }

    @Test
    void aMissingProcFileGivesNone() {
        assertThat(BootIdentity.readKernelBootId(absent())).isEmpty();
    }

    @Test
    void aDirectoryInPlaceOfTheProcFileGivesNone() {
        assertThat(BootIdentity.readKernelBootId(tmp)).isEmpty();
    }

    @Test
    void aJunkProcValueGivesNone() {
        assertThat(BootIdentity.readKernelBootId(procFile("not a boot id"))).isEmpty();
    }

    @Test
    void anEmptyProcFileGivesNone() {
        assertThat(BootIdentity.readKernelBootId(procFile(""))).isEmpty();
    }

    @Test
    void writeFallbackMintsAValidId() {
        String minted = BootIdentity.writeFallbackBootId(state());
        assertThat(BootSession.parseBootId(minted)).isEqualTo(minted);
    }

    @Test
    void writeFallbackCreatesTheDirectory() {
        BootIdentity.writeFallbackBootId(tmp.resolve("deep").resolve("state"));
        assertThat(tmp.resolve("deep").resolve("state").resolve(BootIdentity.FALLBACK_FILENAME))
                .exists();
    }

    @Test
    void writeFallbackGivesADifferentIdEachTime() {
        assertThat(BootIdentity.writeFallbackBootId(tmp)).isNotEqualTo(BootIdentity.writeFallbackBootId(tmp));
    }

    @Test
    void theFallbackIsReadBack() {
        String minted = BootIdentity.writeFallbackBootId(tmp);
        assertThat(BootIdentity.readFallbackBootId(tmp)).contains(minted);
    }

    @Test
    void readingAnAbsentFallbackGivesNone() {
        assertThat(BootIdentity.readFallbackBootId(tmp)).isEmpty();
    }

    @Test
    void readingACorruptFallbackGivesNone() throws IOException {
        Files.writeString(tmp.resolve(BootIdentity.FALLBACK_FILENAME), "junk");
        assertThat(BootIdentity.readFallbackBootId(tmp)).isEmpty();
    }

    @Test
    void theKernelWinsWhenItIsThere() {
        BootIdentity found = BootIdentity.current(state(), procFile(KERNEL_FORM), false);
        assertThat(found.bootId()).isEqualTo(FLAT);
        assertThat(found.source()).isEqualTo("kernel");
        assertThat(found.fromKernel()).isTrue();
    }

    @Test
    void theFallbackIsUsedWhenThereIsNoProc() {
        BootIdentity found = BootIdentity.current(state(), absent(), false);
        assertThat(found.source()).isEqualTo("fallback");
        assertThat(found.fromKernel()).isFalse();
        assertThat(BootSession.parseBootId(found.bootId())).isEqualTo(found.bootId());
    }

    @Test
    void aSecondRunMintsANewFallbackByDefault() {
        BootIdentity first = BootIdentity.current(state(), absent(), false);
        BootIdentity second = BootIdentity.current(state(), absent(), false);
        assertThat(first.bootId()).isNotEqualTo(second.bootId());
    }

    @Test
    void reuseBringsBackTheStoredFallback() {
        BootIdentity first = BootIdentity.current(state(), absent(), false);
        BootIdentity second = BootIdentity.current(state(), absent(), true);
        assertThat(second.bootId()).isEqualTo(first.bootId());
        assertThat(second.source()).isEqualTo("fallback-reused");
    }

    @Test
    void reuseStillMintsWhenThereIsNothingStored() {
        assertThat(BootIdentity.current(state(), absent(), true).source()).isEqualTo("fallback");
    }

    @Test
    void reuseIsIgnoredWhenTheKernelAnswers() {
        BootIdentity.writeFallbackBootId(state());
        assertThat(BootIdentity.current(state(), procFile(KERNEL_FORM), true).bootId())
                .isEqualTo(FLAT);
    }
}
