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
import java.util.Optional;
import com.ikeda.rank.BaselineRanking;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class CandidateStoreTest {

    /** No baseline available: every candidate is unscored. */
    private static final BaselineRanking NO_RANKS = BaselineRanking.NONE;

    private static BaselineRanking ranking(Map<String, Integer> ranks) {
        return new BaselineRanking() {
            @Override
            public java.util.Optional<Integer> rankOf(String lemma) {
                return java.util.Optional.ofNullable(ranks.get(lemma));
            }

            @Override
            public java.util.Set<String> commonest(int limit) {
                return java.util.Set.of();
            }

            @Override
            public int rarerThanAll() {
                return Integer.MAX_VALUE;
            }
        };
    }

    private Database database;
    private CorpusStore corpus;
    private CandidateStore candidates;
    private KnownLemmaStore known;

    @BeforeEach
    void setUp() {
        database = Database.inMemory();
        corpus = new CorpusStore(database);
        candidates = new CandidateStore(database);
        known = new KnownLemmaStore(database);
    }

    @AfterEach
    void tearDown() {
        if (database != null) {
            database.close();
        }
    }

    @Test
    @DisplayName("derives the dispersion floor from the size of the corpus")
    void derivesDispersionFloorFromCorpusSize() {
        ingestAcross(20, "広く出る語");
        ingestAcross(2, "狭く出る語");

        candidates.populate(0.5, NO_RANKS);

        assertThat(candidates.nextBatch(10))
                .extracting(Candidate::key)
                .containsExactly("広く出る語");
    }

    @Test
    @DisplayName("keeps a floor of two filings however small the corpus")
    void keepsAMinimumFloor() {
        ingestAcross(1, "一度きりの語");

        candidates.populate(0.0001, NO_RANKS);

        assertThat(candidates.nextBatch(10)).isEmpty();
    }

    @Test
    @DisplayName("promotes only terms meeting the dispersion floor")
    void appliesDispersionFloor() {
        // 蓋然性 in five filings, 独自語 in one.
        ingestAcross(5, "蓋然性");
        ingestAcross(1, "独自語");

        candidates.populate(3, NO_RANKS);

        assertThat(candidates.nextBatch(100))
                .extracting(Candidate::key)
                .containsExactly("蓋然性");
    }

    @Test
    @DisplayName("excludes single-character terms as fragments")
    void excludesSingleCharacterTerms() {
        ingestAcross(5, "蓋然性", "円");

        candidates.populate(3, NO_RANKS);

        assertThat(candidates.nextBatch(100))
                .extracting(Candidate::key)
                .containsExactly("蓋然性");
    }

    @Test
    @DisplayName("attaches an example sentence from a real filing")
    void attachesExample() {
        ingestAcross(5, "蓋然性");
        candidates.populate(3, NO_RANKS);

        assertThat(candidates.nextBatch(100).getFirst().example())
                .isNotNull()
                .contains("蓋然性");
    }

    @Test
    @DisplayName("carries corpus and document frequency onto the candidate")
    void carriesFrequencies() {
        ingestAcross(4, "蓋然性");
        candidates.populate(3, NO_RANKS);

        Candidate candidate = candidates.nextBatch(100).getFirst();

        assertThat(candidate.documentFrequency()).isEqualTo(4);
        assertThat(candidate.corpusFrequency()).isEqualTo(4);
    }

    @Test
    @DisplayName("records a verdict and removes the candidate from pending")
    void recordsVerdict() {
        ingestAcross(5, "蓋然性");
        candidates.populate(3, NO_RANKS);

        int updated = candidates.recordVerdicts(Map.of("蓋然性", CandidateStatus.WORTH_LEARNING));

        assertThat(updated).isEqualTo(1);
        assertThat(candidates.nextBatch(100)).isEmpty();
        assertThat(candidates.verdictCounts().get(CandidateStatus.WORTH_LEARNING)).isEqualTo(1);
    }

    @Test
    @DisplayName("ignores verdicts for terms that are not candidates")
    void ignoresUnknownTerms() {
        ingestAcross(5, "蓋然性");
        candidates.populate(3, NO_RANKS);

        int updated = candidates.recordVerdicts(Map.of("存在しない語", CandidateStatus.KNOWN));

        assertThat(updated).isZero();
    }

    @Test
    @DisplayName("re-populating preserves verdicts, because they are expensive to produce")
    void preservesVerdictsOnRepopulate() {
        ingestAcross(5, "蓋然性");
        candidates.populate(3, NO_RANKS);
        candidates.recordVerdicts(Map.of("蓋然性", CandidateStatus.KNOWN));

        candidates.populate(3, NO_RANKS);

        assertThat(candidates.verdictCounts().get(CandidateStatus.KNOWN)).isEqualTo(1);
        assertThat(candidates.nextBatch(100)).isEmpty();
    }

    @Test
    @DisplayName("re-populating refreshes counts after more filings arrive")
    void refreshesCountsOnRepopulate() {
        ingestAcross(4, "蓋然性");
        candidates.populate(3, NO_RANKS);
        assertThat(candidates.nextBatch(100).getFirst().documentFrequency()).isEqualTo(4);

        ingestAcross(3, "蓋然性");   // three more filings
        candidates.populate(3, NO_RANKS);

        assertThat(candidates.nextBatch(100).getFirst().documentFrequency()).isEqualTo(7);
    }

    @Test
    @DisplayName("orders a batch by baseline rarity, rarest first")
    void ordersBatchByRarity() {
        ingestAcross(5, "普通の語");
        ingestAcross(5, "珍しい語");
        candidates.populate(3, ranking(Map.of("普通の語", 500, "珍しい語", 40000)));

        assertThat(candidates.nextBatch(10))
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
        candidates.populate(3, ranking(Map.of("既知語", 9000)));

        assertThat(candidates.nextBatch(10))
                .extracting(Candidate::key)
                .containsExactly("既知語", "対象外語");
    }

    @Test
    @DisplayName("counts every status, including those with no candidates")
    void countsAllStatuses() {
        ingestAcross(5, "蓋然性");
        candidates.populate(3, NO_RANKS);

        assertThat(candidates.verdictCounts())
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
        known.add(List.of("既知語"), "anki");

        candidates.populate(3, NO_RANKS);

        assertThat(candidates.nextBatch(10))
                .extracting(Candidate::key)
                .containsExactly("蓋然性");
    }

    @Test
    @DisplayName("drops a pending candidate once the known set catches up with it")
    void dropsCandidateThatBecomesKnown() {
        ingestAcross(5, "蓋然性");
        candidates.populate(3, NO_RANKS);
        assertThat(candidates.nextBatch(10)).hasSize(1);

        known.add(List.of("蓋然性"), "anki");
        candidates.populate(3, NO_RANKS);

        assertThat(candidates.nextBatch(10)).isEmpty();
    }

    @Test
    @DisplayName("adding known lemmas twice is idempotent")
    void addKnownIsIdempotent() {
        assertThat(known.add(List.of("語A", "語B"), "anki")).isEqualTo(2);
        assertThat(known.add(List.of("語A", "語B"), "review")).isZero();
        assertThat(known.count()).isEqualTo(2);
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
