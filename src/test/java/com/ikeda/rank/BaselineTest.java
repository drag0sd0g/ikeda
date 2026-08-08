package com.ikeda.rank;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BaselineTest {

    @TempDir
    Path dir;

    private Path write(String content) throws IOException {
        Path file = dir.resolve("baseline.tsv");
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }

    @Test
    @DisplayName("ranks lemmas by frequency, commonest first")
    void ranksByFrequency() throws IOException {
        Path file = write("""
                rank\tlemma\tpos\tfrequency
                1\t事業\t名詞\t50000
                2\t蓋然性\t名詞\t120
                3\t当社\t名詞\t8000
                """);

        Baseline baseline = Baseline.load(file);

        assertThat(baseline.rankOf("事業")).contains(1);
        assertThat(baseline.rankOf("当社")).contains(2);
        assertThat(baseline.rankOf("蓋然性")).contains(3);
    }

    @Test
    @DisplayName("sums frequencies when a lemma appears under several parts of speech")
    void sumsAcrossPartsOfSpeech() throws IOException {
        Path file = write("""
                rank\tlemma\tpos\tfrequency
                1\t分離\t名詞\t300
                2\t分離\t動詞\t400
                3\t類似\t名詞\t500
                """);

        Baseline baseline = Baseline.load(file);

        // 分離 totals 700, so it outranks 類似 at 500.
        assertThat(baseline.rankOf("分離")).contains(1);
        assertThat(baseline.rankOf("類似")).contains(2);
    }

    @Test
    @DisplayName("reports absence as empty, never as a very large rank")
    void absenceIsEmpty() throws IOException {
        // 23% of candidates are absent and are 74% already known, so treating
        // absence as maximal rarity would put the wrong words first.
        Path file = write("rank\tlemma\tpos\tfrequency\n1\t事業\t名詞\t50000\n");

        assertThat(Baseline.load(file).rankOf("連結損益計算書")).isEmpty();
    }

    @Test
    @DisplayName("skips malformed rows rather than failing on a third-party file")
    void skipsMalformedRows() throws IOException {
        Path file = write("""
                rank\tlemma\tpos\tfrequency
                1\t事業\t名詞\t50000
                2\t壊れた行\t名詞\tnot-a-number
                3\t短い行
                4\t当社\t名詞\t8000
                """);

        Baseline baseline = Baseline.load(file);

        assertThat(baseline.size()).isEqualTo(2);
        assertThat(baseline.rankOf("事業")).contains(1);
        assertThat(baseline.rankOf("当社")).contains(2);
    }

    @Test
    @DisplayName("locates columns by name, not position")
    void locatesColumnsByName() throws IOException {
        Path file = write("""
                lForm\tfrequency\tpmw\tlemma\tpos
                ジギョウ\t50000\t1.2\t事業\t名詞
                """);

        assertThat(Baseline.load(file).rankOf("事業")).contains(1);
    }

    @Test
    @DisplayName("fails loudly if the file is not the expected list")
    void failsOnWrongFile() throws IOException {
        Path file = write("word\tcount\nfoo\t1\n");

        assertThatThrownBy(() -> Baseline.load(file))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("lemma");
    }
}
