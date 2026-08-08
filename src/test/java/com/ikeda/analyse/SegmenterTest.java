package com.ikeda.analyse;

import com.ikeda.ingest.NarrativeBlock;
import com.worksap.nlp.sudachi.Morpheme;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises the real Sudachi tokeniser, so it needs the downloaded dictionary.
 * Skipped rather than failed when absent — the dictionary is ~70MB and gitignored.
 */
@EnabledIf("dictionaryPresent")
class SegmenterTest {

    private static final Path DICTIONARY = Path.of("dict/system_core.dic");

    static boolean dictionaryPresent() {
        return Files.exists(DICTIONARY);
    }

    private static Segmenter segmenter;

    @BeforeAll
    static void setUp() {
        segmenter = new Segmenter(DICTIONARY, ProseFilter.CORPUS);
    }

    @AfterAll
    static void tearDown() {
        if (segmenter != null) {
            segmenter.close();
        }
    }

    @Test
    @DisplayName("splits a block into sentences and drops the trailing table")
    void splitsProseAndDropsTable() {
        var block = new NarrativeBlock("jpcrp_cor:BusinessRisksTextBlock",
                "当社グループは、公共交通事業を営んでおります。"
                        + "業績は天候の影響を受ける可能性があります。"
                        + "売上高（千円）3,054,7143,364,9353,293,367");

        var result = segmenter.segment(List.of(block));

        assertThat(result.sentences()).extracting(Segmenter.Sentence::text)
                .containsExactly(
                        "当社グループは、公共交通事業を営んでおります。",
                        "業績は天候の影響を受ける可能性があります。");
        assertThat(result.stats().prose()).isEqualTo(2);
    }

    @Test
    @DisplayName("deduplicates sentences repeated across 連結 and 個別 blocks")
    void deduplicatesRepeatedSentences() {
        String repeated = "当社の報告セグメントは、取締役会が経営資源の配分を決定しております。";
        var consolidated = new NarrativeBlock("jpcrp_cor:SegmentInfoConsolidatedTextBlock", repeated);
        var standalone = new NarrativeBlock("jpcrp_cor:SegmentInfoTextBlock", repeated);

        var result = segmenter.segment(List.of(consolidated, standalone));

        assertThat(result.sentences()).hasSize(1);
        assertThat(result.stats().prose()).isEqualTo(2);
        assertThat(result.stats().duplicates()).isEqualTo(1);
        assertThat(result.stats().kept()).isEqualTo(1);
    }

    @Test
    @DisplayName("retains the source element ID for traceability")
    void retainsElementId() {
        var block = new NarrativeBlock("jpcrp_cor:BusinessPolicyTextBlock",
                "当社は、中期経営計画に基づき事業の拡大を図っております。");

        var result = segmenter.segment(List.of(block));

        assertThat(result.sentences().getFirst().elementId())
                .isEqualTo("jpcrp_cor:BusinessPolicyTextBlock");
    }

    @Test
    @DisplayName("tokenises in mode C, merging dictionary-attested compounds")
    void tokenisesInModeC() {
        var block = new NarrativeBlock("jpcrp_cor:BusinessRisksTextBlock",
                "将来の課税所得が生じる蓋然性を勘案して判断しております。");

        var result = segmenter.segment(List.of(block));
        List<String> surfaces = result.sentences().getFirst().morphemes().stream()
                .map(Morpheme::surface).toList();

        assertThat(surfaces).contains("課税所得", "蓋然性");
    }

    @Test
    @DisplayName("gives an inflected word the reading of its dictionary form")
    void resolvesDictionaryFormReading() {
        // readingForm() returns the reading of the SURFACE, so 晒される keyed as
        // 晒す would otherwise carry サラサ, and 見て keyed as 見る would carry ミ.
        var block = new NarrativeBlock("jpcrp_cor:BusinessRisksTextBlock",
                "信用リスクに晒されており、状況を見ております。");

        var terms = segmenter.segment(List.of(block)).analysed().getFirst().terms();

        assertThat(terms)
                .filteredOn(t -> t.key().equals("晒す"))
                .allSatisfy(t -> assertThat(t.reading()).isEqualTo("サラス"));
        assertThat(terms)
                .filteredOn(t -> t.key().equals("見る"))
                .allSatisfy(t -> assertThat(t.reading()).isEqualTo("ミル"));
    }

    @Test
    @DisplayName("leaves uninflected words with their own reading")
    void keepsUninflectedReading() {
        var block = new NarrativeBlock("jpcrp_cor:BusinessRisksTextBlock",
                "将来の課税所得が生じる蓋然性を勘案しております。");

        var terms = segmenter.segment(List.of(block)).analysed().getFirst().terms();

        assertThat(terms)
                .filteredOn(t -> t.key().equals("蓋然性"))
                .allSatisfy(t -> assertThat(t.reading()).isEqualTo("ガイゼンセイ"));
    }

    @Test
    @DisplayName("keeps content words and drops particles and numerals")
    void filtersContentWords() {
        var block = new NarrativeBlock("jpcrp_cor:BusinessRisksTextBlock",
                "当社は令和8年に事業を拡大しております。");

        List<String> content = segmenter.segment(List.of(block)).analysed().getFirst().terms()
                .stream().map(TermOccurrence::surface).toList();

        assertThat(content).contains("事業", "拡大");
        assertThat(content).doesNotContain("は", "を", "に", "8");
    }
}
