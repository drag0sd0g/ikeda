package com.ikeda.store;

import com.ikeda.analyse.AnalysedSentence;
import com.ikeda.analyse.TermOccurrence;
import com.ikeda.ingest.FilingRef;
import com.ikeda.ingest.NarrativeBlock;
import com.ikeda.review.Candidate;
import com.ikeda.review.CandidateStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class ReviewStoreTest {

    /** No baseline available: every candidate is unscored. */
    private static final Function<String, Integer> NO_RANKS = lemma -> null;

    private Database database;
    private CorpusStore corpus;
    private ReviewStore review;

    @BeforeEach
    void setUp() {
        database = Database.inMemory();
        corpus = new CorpusStore(database);
        review = new ReviewStore(database);
    }

    @AfterEach
    void tearDown() {
        if (database != null) {
            database.close();
        }
    }

    @Test
    @DisplayName("promotes only terms meeting the dispersion floor")
    void appliesDispersionFloor() {
        // 蓋然性 in five filings, 独自語 in one.
        ingestAcross(5, "蓋然性");
        ingestAcross(1, "独自語");

        review.populate(3, NO_RANKS);

        assertThat(review.nextBatch(100))
                .extracting(Candidate::key)
                .containsExactly("蓋然性");
    }

    @Test
    @DisplayName("excludes single-character terms as fragments")
    void excludesSingleCharacterTerms() {
        ingestAcross(5, "蓋然性", "円");

        review.populate(3, NO_RANKS);

        assertThat(review.nextBatch(100))
                .extracting(Candidate::key)
                .containsExactly("蓋然性");
    }

    @Test
    @DisplayName("attaches an example sentence from a real filing")
    void attachesExample() {
        ingestAcross(5, "蓋然性");
        review.populate(3, NO_RANKS);

        assertThat(review.nextBatch(100).getFirst().example())
                .isNotNull()
                .contains("蓋然性");
    }

    @Test
    @DisplayName("carries corpus and document frequency onto the candidate")
    void carriesFrequencies() {
        ingestAcross(4, "蓋然性");
        review.populate(3, NO_RANKS);

        Candidate candidate = review.nextBatch(100).getFirst();

        assertThat(candidate.documentFrequency()).isEqualTo(4);
        assertThat(candidate.corpusFrequency()).isEqualTo(4);
    }

    @Test
    @DisplayName("records a verdict and removes the candidate from pending")
    void recordsVerdict() {
        ingestAcross(5, "蓋然性");
        review.populate(3, NO_RANKS);

        int updated = review.recordVerdicts(Map.of("蓋然性", CandidateStatus.WORTH_LEARNING));

        assertThat(updated).isEqualTo(1);
        assertThat(review.nextBatch(100)).isEmpty();
        assertThat(review.verdictCounts().get(CandidateStatus.WORTH_LEARNING)).isEqualTo(1);
    }

    @Test
    @DisplayName("ignores verdicts for terms that are not candidates")
    void ignoresUnknownTerms() {
        ingestAcross(5, "蓋然性");
        review.populate(3, NO_RANKS);

        int updated = review.recordVerdicts(Map.of("存在しない語", CandidateStatus.KNOWN));

        assertThat(updated).isZero();
    }

    @Test
    @DisplayName("re-populating preserves verdicts, because they are expensive to produce")
    void preservesVerdictsOnRepopulate() {
        ingestAcross(5, "蓋然性");
        review.populate(3, NO_RANKS);
        review.recordVerdicts(Map.of("蓋然性", CandidateStatus.KNOWN));

        review.populate(3, NO_RANKS);

        assertThat(review.verdictCounts().get(CandidateStatus.KNOWN)).isEqualTo(1);
        assertThat(review.nextBatch(100)).isEmpty();
    }

    @Test
    @DisplayName("re-populating refreshes counts after more filings arrive")
    void refreshesCountsOnRepopulate() {
        ingestAcross(4, "蓋然性");
        review.populate(3, NO_RANKS);
        assertThat(review.nextBatch(100).getFirst().documentFrequency()).isEqualTo(4);

        ingestAcross(3, "蓋然性");   // three more filings
        review.populate(3, NO_RANKS);

        assertThat(review.nextBatch(100).getFirst().documentFrequency()).isEqualTo(7);
    }

    @Test
    @DisplayName("orders a batch by baseline rarity, rarest first")
    void ordersBatchByRarity() {
        ingestAcross(5, "普通の語");
        ingestAcross(5, "珍しい語");
        review.populate(3, Map.of("普通の語", 500, "珍しい語", 40000)::get);

        assertThat(review.nextBatch(10))
                .extracting(Candidate::key)
                .containsExactly("珍しい語", "普通の語");
    }

    @Test
    @DisplayName("places candidates absent from the baseline last, not first")
    void placesUnscoredLast() {
        // Absence usually means the baseline tokenises the compound differently,
        // and such words proved 74% already known — so they must not lead.
        ingestAcross(5, "既知語");
        ingestAcross(5, "対象外語");
        review.populate(3, Map.of("既知語", 9000)::get);

        assertThat(review.nextBatch(10))
                .extracting(Candidate::key)
                .containsExactly("既知語", "対象外語");
    }

    @Test
    @DisplayName("counts every status, including those with no candidates")
    void countsAllStatuses() {
        ingestAcross(5, "蓋然性");
        review.populate(3, NO_RANKS);

        assertThat(review.verdictCounts())
                .containsEntry(CandidateStatus.PENDING, 1L)
                .containsEntry(CandidateStatus.KNOWN, 0L)
                .containsEntry(CandidateStatus.WORTH_LEARNING, 0L)
                .containsEntry(CandidateStatus.NOT_WORTH_LEARNING, 0L);
    }

    @Test
    @DisplayName("never proposes a word already in the known set")
    void excludesKnownLemmas() {
        ingestAcross(5, "蓋然性");
        ingestAcross(5, "既知語");
        review.addKnown(List.of("既知語"), "anki");

        review.populate(3, NO_RANKS);

        assertThat(review.nextBatch(10))
                .extracting(Candidate::key)
                .containsExactly("蓋然性");
    }

    @Test
    @DisplayName("drops a pending candidate once the known set catches up with it")
    void dropsCandidateThatBecomesKnown() {
        ingestAcross(5, "蓋然性");
        review.populate(3, NO_RANKS);
        assertThat(review.nextBatch(10)).hasSize(1);

        review.addKnown(List.of("蓋然性"), "anki");
        review.populate(3, NO_RANKS);

        assertThat(review.nextBatch(10)).isEmpty();
    }

    @Test
    @DisplayName("a 'known' verdict promotes the word into the known set")
    void knownVerdictBecomesKnownLemma() {
        ingestAcross(5, "蓋然性");
        review.populate(3, NO_RANKS);

        review.recordVerdicts(Map.of("蓋然性", CandidateStatus.KNOWN));

        assertThat(review.knownCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("a 'worth learning' verdict does not make the word known")
    void worthVerdictDoesNotBecomeKnown() {
        ingestAcross(5, "蓋然性");
        review.populate(3, NO_RANKS);

        review.recordVerdicts(Map.of("蓋然性", CandidateStatus.WORTH_LEARNING));

        assertThat(review.knownCount()).isZero();
    }

    @Test
    @DisplayName("adding known lemmas twice is idempotent")
    void addKnownIsIdempotent() {
        assertThat(review.addKnown(List.of("語A", "語B"), "anki")).isEqualTo(2);
        assertThat(review.addKnown(List.of("語A", "語B"), "review")).isZero();
        assertThat(review.knownCount()).isEqualTo(2);
    }

    // --- fixtures -------------------------------------------------------

    private static final String ELEMENT = "jpcrp_cor:BusinessRisksTextBlock";

    /** Distinct across the whole test, so every ingest creates a new filing. */
    private int nextFiling;

    /** Puts the given terms into {@code filings} distinct filings, one sentence each. */
    private void ingestAcross(int filings, String... terms) {
        String text = "当社の事業には" + String.join("と", terms) + "が関係しております。";
        var occurrences = IntStream.range(0, terms.length)
                .mapToObj(i -> new TermOccurrence(terms[i], terms[i], "カナ", "名詞", i))
                .toList();

        for (int i = 0; i < filings; i++) {
            String docId = "S%06d".formatted(nextFiling++);
            corpus.ingestFiling(
                    new FilingRef(docId, "E0001", "会社" + docId, "120", "010", "030000",
                            "2026-06-26 09:00", true),
                    List.of(new NarrativeBlock(ELEMENT, text)),
                    List.of(new AnalysedSentence(0, 0, ELEMENT, text, 12, occurrences)));
        }
    }
}
