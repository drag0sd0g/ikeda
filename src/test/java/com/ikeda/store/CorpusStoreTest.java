package com.ikeda.store;

import com.ikeda.analyse.AnalysedSentence;
import com.ikeda.analyse.TermOccurrence;
import com.ikeda.ingest.FilingRef;
import com.ikeda.ingest.NarrativeBlock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CorpusStoreTest {

    private Database database;
    private CorpusStore store;

    @BeforeEach
    void setUp() {
        database = Database.inMemory();
        store = new CorpusStore(database);
    }

    @AfterEach
    void tearDown() {
        if (database != null) {
            database.close();
        }
    }

    @Nested
    @DisplayName("ingestion")
    class Ingestion {

        @Test
        @DisplayName("stores a filing with its blocks, sentences, terms and occurrences")
        void storesEverything() {
            store.ingestFiling(filing("S100AAAA", "株式会社アルファ"),
                    List.of(block("BusinessRisks", "為替変動のリスクがあります。")),
                    List.of(sentence(0, 0, "BusinessRisks", "為替変動のリスクがあります。",
                            term("為替", 0), term("変動", 2), term("リスク", 5))));

            assertThat(store.stats())
                    .isEqualTo(new CorpusStats(1, 1, 1, 3, 3));
        }

        @Test
        @DisplayName("reports a filing as present only after it is ingested")
        void tracksPresence() {
            assertThat(store.hasFiling("S100AAAA")).isFalse();

            store.ingestFiling(filing("S100AAAA", "株式会社アルファ"),
                    List.of(block("BusinessRisks", "為替変動のリスクがあります。")),
                    List.of(sentence(0, 0, "BusinessRisks", "為替変動のリスクがあります。",
                            term("為替", 0))));

            assertThat(store.hasFiling("S100AAAA")).isTrue();
            assertThat(store.hasFiling("S100BBBB")).isFalse();
        }

        @Test
        @DisplayName("records sentence length and token count")
        void recordsSentenceMetrics() {
            String text = "為替変動のリスクがあります。";
            store.ingestFiling(filing("S100AAAA", "株式会社アルファ"),
                    List.of(block("BusinessRisks", text)),
                    List.of(new AnalysedSentence(0, 0, "BusinessRisks", text, 9,
                            List.of(term("為替", 0)))));

            assertThat(store.topTermsByDocumentFrequency(10)).hasSize(1);
            assertThat(store.stats().sentences()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("terms across filings")
    class TermsAcrossFilings {

        @Test
        @DisplayName("shares one term row across filings and counts document frequency")
        void sharesTermsAcrossFilings() {
            ingest("S100AAAA", "為替変動のリスクがあります。", term("為替", 0), term("リスク", 5));
            ingest("S100BBBB", "為替の影響を受けます。", term("為替", 0), term("影響", 3));

            // 為替 in both, リスク and 影響 in one each — three distinct terms.
            assertThat(store.stats().terms()).isEqualTo(3);
            assertThat(store.stats().occurrences()).isEqualTo(4);

            assertThat(store.topTermsByDocumentFrequency(10))
                    .extracting(TermFrequency::key, TermFrequency::documentFrequency)
                    .containsExactly(
                            org.assertj.core.api.Assertions.tuple("為替", 2L),
                            org.assertj.core.api.Assertions.tuple("リスク", 1L),
                            org.assertj.core.api.Assertions.tuple("影響", 1L));
        }

        @Test
        @DisplayName("counts repeated occurrences of a term within one sentence")
        void countsRepeatsWithinASentence() {
            ingest("S100AAAA", "リスクとリスクがあります。", term("リスク", 0), term("リスク", 4));

            assertThat(store.topTermsByDocumentFrequency(10))
                    .singleElement()
                    .satisfies(t -> {
                        assertThat(t.corpusFrequency()).isEqualTo(2);
                        assertThat(t.documentFrequency()).isEqualTo(1);
                    });
        }

        @Test
        @DisplayName("keeps identical sentences from different filings, so document frequency is honest")
        void keepsIdenticalSentencesAcrossFilings() {
            String boilerplate = "該当事項はありません。";
            ingest("S100AAAA", boilerplate, term("該当", 0), term("事項", 2));
            ingest("S100BBBB", boilerplate, term("該当", 0), term("事項", 2));

            assertThat(store.stats().sentences()).isEqualTo(2);
            assertThat(store.topTermsByDocumentFrequency(10))
                    .allSatisfy(t -> assertThat(t.documentFrequency()).isEqualTo(2));
        }
    }

    @Nested
    @DisplayName("atomicity")
    class Atomicity {

        @Test
        @DisplayName("leaves nothing behind when a filing fails part way through")
        void rollsBackFailedFiling() {
            ingest("S100AAAA", "為替変動のリスクがあります。", term("為替", 0));
            CorpusStats before = store.stats();

            // Duplicate sentence text within one filing violates UNIQUE(doc_id, text).
            String duplicated = "同じ文です。";
            assertThatThrownBy(() -> store.ingestFiling(
                    filing("S100BBBB", "株式会社ベータ"),
                    List.of(block("BusinessRisks", duplicated)),
                    List.of(sentence(0, 0, "BusinessRisks", duplicated, term("同じ", 0)),
                            sentence(0, 1, "BusinessRisks", duplicated, term("同じ", 0)))))
                    .isInstanceOf(StoreException.class);

            assertThat(store.stats()).isEqualTo(before);
            assertThat(store.hasFiling("S100BBBB")).isFalse();
        }

        @Test
        @DisplayName("stays usable after a failed ingestion")
        void recoversAfterFailure() {
            String duplicated = "同じ文です。";
            assertThatThrownBy(() -> store.ingestFiling(
                    filing("S100BBBB", "株式会社ベータ"),
                    List.of(block("BusinessRisks", duplicated)),
                    List.of(sentence(0, 0, "BusinessRisks", duplicated, term("同じ", 0)),
                            sentence(0, 1, "BusinessRisks", duplicated, term("同じ", 0)))))
                    .isInstanceOf(StoreException.class);

            ingest("S100CCCC", "為替変動のリスクがあります。", term("為替", 0));

            assertThat(store.hasFiling("S100CCCC")).isTrue();
            assertThat(store.stats().filings()).isEqualTo(1);
        }

        @Test
        @DisplayName("rejects a sentence pointing at a block that does not exist")
        void rejectsDanglingBlockIndex() {
            assertThatThrownBy(() -> store.ingestFiling(
                    filing("S100AAAA", "株式会社アルファ"),
                    List.of(block("BusinessRisks", "本文です。")),
                    List.of(sentence(5, 0, "BusinessRisks", "本文です。", term("本文", 0)))))
                    .isInstanceOf(StoreException.class);

            assertThat(store.hasFiling("S100AAAA")).isFalse();
        }
    }

    @Nested
    @DisplayName("resumability")
    class Resumability {

        @Test
        @DisplayName("an already ingested filing is detectable, so a run can resume")
        void supportsResume() {
            List<FilingRef> batch = List.of(
                    filing("S100AAAA", "株式会社アルファ"),
                    filing("S100BBBB", "株式会社ベータ"),
                    filing("S100CCCC", "株式会社ガンマ"));

            // First pass stops after two filings, as if interrupted.
            for (FilingRef ref : batch.subList(0, 2)) {
                store.ingestFiling(ref,
                        List.of(block("BusinessRisks", "為替変動のリスクがあります。")),
                        List.of(sentence(0, 0, "BusinessRisks", "為替変動のリスクがあります。",
                                term("為替", 0))));
            }

            long fetched = batch.stream().filter(ref -> !store.hasFiling(ref.docId())).count();

            assertThat(fetched).isEqualTo(1);
            assertThat(store.stats().filings()).isEqualTo(2);
        }
    }

    @Nested
    @DisplayName("schema")
    class Schema {

        @Test
        @DisplayName("an empty store reports zero everywhere")
        void startsEmpty() {
            assertThat(store.stats()).isEqualTo(new CorpusStats(0, 0, 0, 0, 0));
            assertThat(store.topTermsByDocumentFrequency(10)).isEmpty();
        }

        @Test
        @DisplayName("applying the schema twice is harmless")
        void schemaIsIdempotent() {
            try (Database reopened = Database.inMemory()) {
                assertThat(new CorpusStore(reopened).stats().filings()).isZero();
            }
        }
    }

    // --- fixtures -------------------------------------------------------

    private void ingest(String docId, String text, TermOccurrence... terms) {
        store.ingestFiling(filing(docId, "株式会社" + docId),
                List.of(block("BusinessRisks", text)),
                List.of(sentence(0, 0, "BusinessRisks", text, terms)));
    }

    private static FilingRef filing(String docId, String filerName) {
        return new FilingRef(docId, "E00001", filerName, "120", "010", "030000",
                "2026-06-26 09:00", true);
    }

    private static NarrativeBlock block(String element, String text) {
        return new NarrativeBlock("jpcrp_cor:" + element + "TextBlock", text);
    }

    private static AnalysedSentence sentence(int blockIndex, int seq, String element,
                                             String text, TermOccurrence... terms) {
        return new AnalysedSentence(blockIndex, seq, "jpcrp_cor:" + element + "TextBlock",
                text, text.length(), List.of(terms));
    }

    private static TermOccurrence term(String key, int position) {
        return new TermOccurrence(key, key, "カナ", "名詞", position);
    }
}
