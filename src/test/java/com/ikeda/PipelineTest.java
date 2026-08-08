package com.ikeda;

import com.ikeda.analyse.ProseFilter;
import com.ikeda.analyse.Segmenter;
import com.ikeda.ingest.DocumentFilter;
import com.ikeda.ingest.Extraction;
import com.ikeda.ingest.NarrativeExtractor;
import com.ikeda.rank.BaselineRanking;
import com.ikeda.review.Candidate;
import com.ikeda.review.CandidateStatus;
import com.ikeda.review.ReviewSheet;
import com.ikeda.store.CandidateStore;
import com.ikeda.store.CorpusStore;
import com.ikeda.store.Database;
import com.ikeda.store.KnownLemmaStore;
import com.ikeda.testing.Fixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIf("dictionaryPresent")
class PipelineTest {

    private static final Path DICTIONARY = Path.of("dict/system_core.dic");

    static boolean dictionaryPresent() {
        return Files.exists(DICTIONARY);
    }

    private Database database;
    private Segmenter segmenter;

    @BeforeEach
    void setUp() {
        database = Database.inMemory();
        segmenter = new Segmenter(DICTIONARY, ProseFilter.CORPUS);
    }

    @AfterEach
    void tearDown() {
        if (segmenter != null) {
            segmenter.close();
        }
        if (database != null) {
            database.close();
        }
    }

    private static byte[] filingBundle(String docId, String prose) {
        return Fixtures.zipBundle(Map.of(
                "XBRL_TO_CSV/jpaud-aar-cn-001_%s.csv".formatted(docId),
                Fixtures.csv(Fixtures.csvRow("jpaud_cor:AuditOpinionTextBlock",
                        "独立監査人の監査報告書であります。")),
                "XBRL_TO_CSV/jpcrp030000-asr-001_%s.csv".formatted(docId),
                Fixtures.csv(Fixtures.csvRow(Fixtures.RISKS_ELEMENT, prose))));
    }

    private void ingest(String docId, String prose) {
        var extractor = new NarrativeExtractor(
                DocumentFilter.CORPORATE_ANNUAL_REPORT.taxonomyPrefix());
        Extraction extraction = extractor.extract(filingBundle(docId, prose));
        var segmentation = segmenter.segment(extraction.blocks());
        new CorpusStore(database)
                .ingestFiling(Fixtures.filing(docId), extraction.blocks(), segmentation.analysed());
    }

    @Test
    @DisplayName("carries a word from a filing bundle through to a review sheet")
    void endToEnd() {
        String prose = "当社は為替変動の影響を受ける可能性があります。"
                + "売上高（千円）3,054,7143,364,935";
        for (int i = 0; i < 5; i++) {
            ingest("S10000%d".formatted(i), prose);
        }

        var corpus = new CorpusStore(database);
        assertThat(corpus.stats().filings()).isEqualTo(5);
        assertThat(corpus.stats().sentences()).isEqualTo(5);

        var candidates = new CandidateStore(database);
        candidates.populate(3, ranking(Map.of("為替", 30_000, "変動", 2_000)));

        List<Candidate> batch = candidates.nextBatch(10);
        assertThat(batch).extracting(Candidate::key).contains("為替", "変動");

        assertThat(batch).extracting(Candidate::key)
                .doesNotContain("独立", "監査", "監査報告書");

        assertThat(batch.getFirst().key()).isEqualTo("為替");
        assertThat(batch.getFirst().example()).contains("為替");

        String sheet = ReviewSheet.write(batch);
        Map<String, CandidateStatus> verdicts = ReviewSheet.readVerdicts(
                sheet.replaceFirst("(?m)^\t為替", "k\t為替"));
        assertThat(verdicts).containsEntry("為替", CandidateStatus.KNOWN);

        candidates.recordVerdicts(verdicts);
        new KnownLemmaStore(database).add(List.of("為替"), "review");
        candidates.populate(3, BaselineRanking.NONE);

        assertThat(candidates.nextBatch(10)).extracting(Candidate::key)
                .doesNotContain("為替");
    }

    @Test
    @DisplayName("drops table text and audit boilerplate before anything is stored")
    void filtersNonProse() {
        ingest("S100AAA", "売上高（千円）3,054,7143,364,9353,293,367");

        assertThat(new CorpusStore(database).stats().sentences()).isZero();
    }

    private static BaselineRanking ranking(Map<String, Integer> ranks) {
        return lemma -> Optional.ofNullable(ranks.get(lemma));
    }
}
