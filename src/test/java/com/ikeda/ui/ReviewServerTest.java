package com.ikeda.ui;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ikeda.analyse.AnalysedSentence;
import com.ikeda.analyse.TermOccurrence;
import com.ikeda.gloss.Gloss;
import com.ikeda.gloss.GlossSource;
import com.ikeda.ingest.FilingRef;
import com.ikeda.ingest.NarrativeBlock;
import com.ikeda.rank.BaselineRanking;
import com.ikeda.review.CandidateStatus;
import com.ikeda.store.CandidateStore;
import com.ikeda.store.CompoundStore;
import com.ikeda.store.CorpusStore;
import com.ikeda.store.Database;
import com.ikeda.store.KnownLemmaStore;
import com.ikeda.store.SentenceStore;
import com.ikeda.store.VerdictRecorder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ReviewServerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String ELEMENT = "jpcrp_cor:BusinessRisksTextBlock";

    private Database database;
    private ReviewServer server;
    private final HttpClient client = HttpClient.newHttpClient();
    private int nextFiling;

    @BeforeEach
    void setUp() {
        database = Database.inMemory();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.close();
        }
        if (database != null) {
            database.close();
        }
    }

    private void ingest(int filings, String sentence, String... terms) {
        var corpus = new CorpusStore(database);
        var occurrences = java.util.stream.IntStream.range(0, terms.length)
                .mapToObj(i -> new TermOccurrence(terms[i], terms[i], "カナ", "名詞", i))
                .toList();
        for (int i = 0; i < filings; i++) {
            String docId = "S%06d".formatted(nextFiling++);
            corpus.ingestFiling(
                    new FilingRef(docId, "E1", "会社" + docId, "120", "010", "030000",
                            "2026-06-26 09:00", true),
                    List.of(new NarrativeBlock(ELEMENT, sentence)),
                    List.of(new AnalysedSentence(0, 0, ELEMENT, sentence, 12, occurrences)));
        }
    }

    private void startServer(GlossSource glosses) {
        var candidates = new CandidateStore(database);
        candidates.populate(3, BaselineRanking.NONE);
        var queue = new ReviewQueue(candidates, new SentenceStore(database),
                new CompoundStore(database), new VerdictRecorder(database), glosses);
        server = new ReviewServer(queue, 0);
        server.start();
    }

    private JsonNode get(String path) throws Exception {
        HttpResponse<String> response = client.send(
                HttpRequest.newBuilder(URI.create(server.address() + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        return MAPPER.readTree(response.body());
    }

    private HttpResponse<String> post(String path, String json) throws Exception {
        return client.send(
                HttpRequest.newBuilder(URI.create(server.address() + path))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                        .build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("serves the review page")
    void servesPage() throws Exception {
        ingest(5, "余資運用について定めております。", "余資", "運用");
        startServer(GlossSource.NONE);

        HttpResponse<String> response = client.send(
                HttpRequest.newBuilder(URI.create(server.address() + "/")).GET().build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type"))
                .contains("text/html; charset=utf-8");
        assertThat(response.body()).contains("Ikeda").contains("api/verdict");
    }

    @Test
    @DisplayName("serves pending candidates with their example sentences")
    void servesQueue() throws Exception {
        ingest(5, "余資運用について定めております。", "余資", "運用");
        startServer(GlossSource.NONE);

        JsonNode queue = get("/api/queue");

        assertThat(queue.isArray()).isTrue();
        assertThat(queue).isNotEmpty();
        JsonNode first = queue.get(0);
        assertThat(first.path("term").asText()).isNotBlank();
        assertThat(first.path("examples")).isNotEmpty();
        assertThat(first.path("examples").get(0).path("text").asText())
                .isEqualTo("余資運用について定めております。");
    }

    @Test
    @DisplayName("includes the meaning so the page can reveal it on request")
    void includesMeaning() throws Exception {
        ingest(5, "余資運用について定めております。", "余資");
        startServer(headword -> headword.equals("余資")
                ? Optional.of(new Gloss("余資", "ヨシ", List.of("surplus funds")))
                : Optional.empty());

        JsonNode queue = get("/api/queue");

        assertThat(queue.get(0).path("meaning").asText()).isEqualTo("surplus funds");
    }

    @Test
    @DisplayName("records a verdict and drops the word from the queue")
    void recordsVerdict() throws Exception {
        ingest(5, "余資運用について定めております。", "余資");
        startServer(GlossSource.NONE);
        assertThat(get("/api/queue")).hasSize(1);

        HttpResponse<String> response =
                post("/api/verdict", "{\"term\":\"余資\",\"verdict\":\"w\"}");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(get("/api/queue")).isEmpty();
        assertThat(get("/api/progress").path("WORTH_LEARNING").asLong()).isEqualTo(1);
    }

    @Test
    @DisplayName("a known verdict retires the word permanently")
    void knownVerdictRetiresWord() throws Exception {
        ingest(5, "余資運用について定めております。", "余資");
        startServer(GlossSource.NONE);

        post("/api/verdict", "{\"term\":\"余資\",\"verdict\":\"k\"}");

        assertThat(new KnownLemmaStore(database).count()).isEqualTo(1);
    }

    @Test
    @DisplayName("undo puts a word back into the queue")
    void undoRestoresWord() throws Exception {
        ingest(5, "余資運用について定めております。", "余資");
        startServer(GlossSource.NONE);
        post("/api/verdict", "{\"term\":\"余資\",\"verdict\":\"n\"}");
        assertThat(get("/api/queue")).isEmpty();

        post("/api/undo", "{\"term\":\"余資\"}");

        assertThat(get("/api/queue")).hasSize(1);
        assertThat(get("/api/progress").path("PENDING").asLong()).isEqualTo(1);
    }

    @Test
    @DisplayName("rejects a verdict it cannot interpret")
    void rejectsBadVerdict() throws Exception {
        ingest(5, "余資運用について定めております。", "余資");
        startServer(GlossSource.NONE);

        assertThat(post("/api/verdict", "{\"term\":\"余資\",\"verdict\":\"\"}").statusCode())
                .isEqualTo(400);
        assertThat(post("/api/undo", "{}").statusCode()).isEqualTo(400);
    }

    @Test
    @DisplayName("reports progress across every verdict")
    void reportsProgress() throws Exception {
        ingest(5, "余資と戻入について定めております。", "余資", "戻入");
        startServer(GlossSource.NONE);
        post("/api/verdict", "{\"term\":\"余資\",\"verdict\":\"k\"}");

        JsonNode progress = get("/api/progress");

        assertThat(progress.path("KNOWN").asLong()).isEqualTo(1);
        assertThat(progress.path("PENDING").asLong()).isEqualTo(1);
        for (CandidateStatus status : CandidateStatus.values()) {
            assertThat(progress.has(status.name())).isTrue();
        }
    }

    @Test
    @DisplayName("serves an empty queue rather than failing when nothing is pending")
    void handlesEmptyQueue() throws Exception {
        startServer(GlossSource.NONE);

        assertThat(get("/api/queue")).isEmpty();
        assertThat(get("/api/progress").path("PENDING").asLong()).isZero();
    }

    @Test
    @DisplayName("exposes compound parts, so transparency can be judged at a glance")
    void exposesCompoundParts() throws Exception {
        ingest(5, "貸倒引当金を計上しております。", "貸倒引当金");
        new CompoundStore(database).store(
                List.of(new CompoundStore.AcceptedCompound(
                        new com.ikeda.compound.CompoundCandidate("貸倒引当金", "カシダオレヒキアテキン",
                                List.of("貸倒", "引当金"), List.of("貸し倒れ", "引き当て", "金")),
                        5, 9.0)),
                java.util.Map.of(), java.util.Map.of());
        startServer(GlossSource.NONE);

        JsonNode item = get("/api/queue").get(0);

        assertThat(item.path("term").asText()).isEqualTo("貸倒引当金");
        assertThat(item.path("parts")).hasSize(2);
    }

    @Test
    @DisplayName("uses a known set that grows as verdicts arrive")
    void knownSetGrows() throws Exception {
        ingest(5, "余資と戻入について定めております。", "余資", "戻入");
        startServer(GlossSource.NONE);
        assertThat(new KnownLemmaStore(database).count()).isZero();

        post("/api/verdict", "{\"term\":\"余資\",\"verdict\":\"k\"}");
        post("/api/verdict", "{\"term\":\"戻入\",\"verdict\":\"w\"}");

        assertThat(new KnownLemmaStore(database).count()).isEqualTo(1);
    }

    @Test
    @DisplayName("never shows the same example sentence twice")
    void deduplicatesExamples() throws Exception {
        ingest(6, "余資運用について定めております。", "余資");
        startServer(GlossSource.NONE);

        JsonNode examples = get("/api/queue").get(0).path("examples");

        assertThat(examples).hasSize(1);
    }

    @Test
    @DisplayName("binds to a free port when asked for one")
    void bindsToFreePort() {
        startServer(GlossSource.NONE);

        assertThat(server.port()).isPositive();
        assertThat(server.address()).startsWith("http://127.0.0.1:");
    }

    @Test
    @DisplayName("a decided word never comes back into the queue")
    void queueNeverRepeatsDecidedWords() throws Exception {
        ingest(5, "余資と戻入について定めております。", "余資", "戻入");
        startServer(GlossSource.NONE);

        post("/api/verdict", "{\"term\":\"余資\",\"verdict\":\"w\"}");
        Set<String> remaining = new java.util.HashSet<>();
        get("/api/queue").forEach(node -> remaining.add(node.path("term").asText()));

        assertThat(remaining).doesNotContain("余資");
    }
}
