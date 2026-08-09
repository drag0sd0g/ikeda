package com.ikeda.gloss;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class JmdictGlossSourceTest {

    @TempDir
    Path dir;

    private Path dictionary(String words) throws IOException {
        Path file = dir.resolve("jmdict.json");
        Files.writeString(file,
                "{\"version\":\"1\",\"languages\":[\"eng\"],\"words\":[%s]}".formatted(words),
                StandardCharsets.UTF_8);
        return file;
    }

    private static String entry(String kanji, String kana, String... glosses) {
        String glossJson = String.join(",", java.util.Arrays.stream(glosses)
                .map(g -> "{\"lang\":\"eng\",\"text\":\"%s\"}".formatted(g)).toList());
        String kanjiJson = kanji.isEmpty() ? "" : "{\"text\":\"%s\"}".formatted(kanji);
        return """
                {"id":"1","kanji":[%s],"kana":[{"text":"%s"}],
                 "sense":[{"partOfSpeech":["n"],"gloss":[%s]}]}
                """.formatted(kanjiJson, kana, glossJson);
    }

    @Test
    @DisplayName("looks a word up by its written form")
    void looksUpByWrittenForm() throws IOException {
        var source = JmdictGlossSource.load(dictionary(
                entry("往査", "おうさ", "site visit")));

        assertThat(source.lookup("往査")).isPresent()
                .get().extracting(Gloss::meaningLine).isEqualTo("site visit");
    }

    @Test
    @DisplayName("joins several senses into one meaning line")
    void joinsMeanings() throws IOException {
        var source = JmdictGlossSource.load(dictionary(
                entry("戻入", "れいにゅう", "reversal of monies", "funds", "commissions")));

        assertThat(source.lookup("戻入")).get()
                .extracting(Gloss::meaningLine)
                .isEqualTo("reversal of monies; funds; commissions");
    }

    @Test
    @DisplayName("keeps the reading alongside the meaning")
    void keepsReading() throws IOException {
        var source = JmdictGlossSource.load(dictionary(entry("往査", "おうさ", "site visit")));

        assertThat(source.lookup("往査")).get().extracting(Gloss::reading).isEqualTo("おうさ");
    }

    @Test
    @DisplayName("indexes kana-only entries by their kana")
    void indexesKanaOnlyEntries() throws IOException {
        var source = JmdictGlossSource.load(dictionary(entry("", "ヘッジ", "hedge")));

        assertThat(source.lookup("ヘッジ")).isPresent();
    }

    @Test
    @DisplayName("reports absence rather than inventing a meaning")
    void reportsAbsence() throws IOException {
        var source = JmdictGlossSource.load(dictionary(entry("往査", "おうさ", "site visit")));

        assertThat(source.lookup("末残")).isEmpty();
    }

    @Test
    @DisplayName("skips entries with no English gloss")
    void skipsEntriesWithoutEnglish() throws IOException {
        var source = JmdictGlossSource.load(dictionary("""
                {"id":"1","kanji":[{"text":"末残"}],"kana":[{"text":"まつざん"}],
                 "sense":[{"partOfSpeech":["n"],"gloss":[{"lang":"ger","text":"Endsaldo"}]}]}
                """));

        assertThat(source.lookup("末残")).isEmpty();
        assertThat(source.size()).isZero();
    }

    @Test
    @DisplayName("the empty source never resolves anything")
    void emptySourceResolvesNothing() {
        assertThat(GlossSource.NONE.lookup("往査")).isEmpty();
    }
}
