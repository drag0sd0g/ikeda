package com.ikeda.store;

import com.ikeda.analyse.AnalysedSentence;
import com.ikeda.analyse.TermOccurrence;
import com.ikeda.ingest.FilingRef;
import com.ikeda.ingest.NarrativeBlock;
import com.ikeda.rank.BaselineRanking;
import com.ikeda.review.CandidateStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class VerdictRecorderTest {

    @TempDir
    Path dir;

    private static final String ELEMENT = "jpcrp_cor:BusinessRisksTextBlock";

    private static void seed(Database database, String term, int filings) {
        var corpus = new CorpusStore(database);
        String text = term + "について定めております。";
        for (int i = 0; i < filings; i++) {
            String docId = "S%06d".formatted(i + term.hashCode() % 1000 + 1000);
            corpus.ingestFiling(
                    new FilingRef(docId, "E1", "会社", "120", "010", "030000",
                            "2026-06-26 09:00", true),
                    List.of(new NarrativeBlock(ELEMENT, text)),
                    List.of(new AnalysedSentence(0, 0, ELEMENT, text, 8,
                            List.of(new TermOccurrence(term, term, "カナ", "名詞", 0)))));
        }
        new CandidateStore(database).populate(3, BaselineRanking.NONE);
    }

    @Test
    @DisplayName("a known verdict also enters the known set")
    void knownVerdictEntersKnownSet() {
        try (Database database = Database.inMemory()) {
            seed(database, "余資", 5);

            new VerdictRecorder(database).record("余資", CandidateStatus.KNOWN);

            assertThat(new KnownLemmaStore(database).count()).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("a worth-learning verdict does not enter the known set")
    void worthVerdictStaysOutOfKnownSet() {
        try (Database database = Database.inMemory()) {
            seed(database, "余資", 5);

            new VerdictRecorder(database).record("余資", CandidateStatus.WORTH_LEARNING);

            assertThat(new KnownLemmaStore(database).count()).isZero();
        }
    }

    @Test
    @DisplayName("undo returns the word to the queue")
    void undoReturnsWordToQueue() {
        try (Database database = Database.inMemory()) {
            seed(database, "余資", 5);
            var recorder = new VerdictRecorder(database);
            recorder.record("余資", CandidateStatus.WORTH_LEARNING);

            recorder.reset("余資");

            assertThat(new CandidateStore(database).verdictCounts())
                    .containsEntry(CandidateStatus.PENDING, 1L)
                    .containsEntry(CandidateStatus.WORTH_LEARNING, 0L);
        }
    }

    @Test
    @DisplayName("a verdict survives closing and reopening the database")
    void verdictSurvivesReopen() {
        Path file = dir.resolve("verdicts.db");
        try (Database database = Database.open(file)) {
            seed(database, "余資", 5);
            new VerdictRecorder(database).record("余資", CandidateStatus.WORTH_LEARNING);
        }
        try (Database reopened = Database.open(file)) {
            assertThat(new CandidateStore(reopened).verdictCounts())
                    .containsEntry(CandidateStatus.WORTH_LEARNING, 1L);
        }
    }

    @Test
    @DisplayName("an undo survives closing and reopening the database")
    void undoSurvivesReopen() {
        Path file = dir.resolve("undo.db");
        try (Database database = Database.open(file)) {
            seed(database, "余資", 5);
            var recorder = new VerdictRecorder(database);
            recorder.record("余資", CandidateStatus.WORTH_LEARNING);
            recorder.reset("余資");
        }
        try (Database reopened = Database.open(file)) {
            assertThat(new CandidateStore(reopened).verdictCounts())
                    .containsEntry(CandidateStatus.PENDING, 1L)
                    .containsEntry(CandidateStatus.WORTH_LEARNING, 0L);
        }
    }

    @Test
    @DisplayName("records a whole sheet at once")
    void recordsManyVerdicts() {
        try (Database database = Database.inMemory()) {
            seed(database, "余資", 5);
            seed(database, "戻入", 5);

            int updated = new VerdictRecorder(database).record(Map.of(
                    "余資", CandidateStatus.KNOWN, "戻入", CandidateStatus.WORTH_LEARNING));

            assertThat(updated).isEqualTo(2);
            assertThat(new KnownLemmaStore(database).count()).isEqualTo(1);
        }
    }
}
