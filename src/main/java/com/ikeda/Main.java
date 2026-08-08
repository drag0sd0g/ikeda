package com.ikeda;

import com.ikeda.analyse.ProseFilter;
import com.ikeda.analyse.Segmenter;
import com.ikeda.ingest.DocumentFilter;
import com.ikeda.ingest.EdinetApi;
import com.ikeda.ingest.EdinetCatalogue;
import com.ikeda.ingest.EdinetException;
import com.ikeda.ingest.Extraction;
import com.ikeda.ingest.FilingRef;
import com.ikeda.ingest.NarrativeExtractor;
import com.ikeda.anki.AnkiConnect;
import com.ikeda.rank.Baseline;
import com.ikeda.review.Candidate;
import com.ikeda.review.CandidateStatus;
import com.ikeda.review.ReviewSheet;
import com.ikeda.store.CorpusStore;
import com.ikeda.store.Database;
import com.ikeda.store.ReviewStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Commands:
 *
 * <pre>
 *   ingest &lt;date&gt; [limit]      fetch, segment and store filings
 *   anki                        load the Anki collection into the known set
 *   sample [size] [out.tsv]     write the next review batch, rarest first
 *   export [out.tsv]            write every decided candidate
 *   verdicts &lt;in.tsv&gt;         read a reviewed sheet back
 *   status                      show corpus and review counts
 * </pre>
 *
 * <p>Ingestion is safe to interrupt and re-run: filings already stored are
 * skipped. Its limit bounds how many filings a run <em>newly</em> ingests, so
 * repeated runs accumulate and an interrupted pass resumes where it stopped.
 */
public class Main {

    private static final Logger log = LoggerFactory.getLogger(Main.class);

    private static final Path SYSTEM_DICTIONARY = Path.of("dict/system_core.dic");
    private static final Path DATABASE = Path.of("ikeda.db");
    private static final Path DEFAULT_SHEET = Path.of("review_batch1.tsv");
    private static final Path BASELINE =
            Path.of("baseline/BCCWJ_frequencylist_suw_ver1_0.tsv");

    /** Dispersion floor: 5% of 181 filings. Below this a term is one company's jargon. */
    private static final int MIN_DOCUMENT_FREQUENCY = 9;

    private static final int DEFAULT_BATCH_SIZE = 150;
    private static final int TOP_TERMS_REPORTED = 20;

    void main(String[] args) {
        String command = args.length > 0 ? args[0] : "status";
        String[] rest = args.length > 1 ? java.util.Arrays.copyOfRange(args, 1, args.length)
                : new String[0];

        try (Database database = Database.open(DATABASE)) {
            switch (command) {
                case "ingest" -> ingest(database, rest);
                case "anki" -> anki(database);
                case "sample" -> sample(database, rest);
                case "export" -> export(database, rest);
                case "verdicts" -> verdicts(database, rest);
                case "status" -> status(database);
                default -> throw new IllegalArgumentException(
                        ("unknown command '%s' — try ingest, anki, sample, "
                                + "export, verdicts or status").formatted(command));
            }
        }
    }

    // --- ingest ---------------------------------------------------------

    private static void ingest(Database database, String[] args) {
        String apiKey = System.getenv("EDINET_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("EDINET_KEY not set");
        }

        LocalDate date = args.length > 0 ? LocalDate.parse(args[0]) : LocalDate.of(2026, 6, 26);
        int limit = args.length > 1 ? Integer.parseInt(args[1]) : Integer.MAX_VALUE;

        var filter = DocumentFilter.CORPORATE_ANNUAL_REPORT;
        var api = EdinetApi.withDefaults(apiKey);
        var catalogue = new EdinetCatalogue(api);
        var extractor = new NarrativeExtractor(filter.taxonomyPrefix());
        var store = new CorpusStore(database);

        List<FilingRef> filings = catalogue.filingsOn(date, filter);
        if (filings.isEmpty()) {
            log.warn("no filings matched on {}", date);
            return;
        }

        try (var segmenter = new Segmenter(SYSTEM_DICTIONARY, ProseFilter.CORPUS)) {
            int ingested = 0;
            int skipped = 0;
            int failed = 0;

            for (FilingRef filing : filings) {
                if (ingested >= limit) {
                    break;
                }
                if (store.hasFiling(filing.docId())) {
                    skipped++;
                    continue;
                }
                try {
                    Extraction extraction = extractor.extract(api.fetchCsvBundle(filing.docId()));
                    var segmentation = segmenter.segment(extraction.blocks());
                    store.ingestFiling(filing, extraction.blocks(), segmentation.analysed());

                    ingested++;
                    log.info("[{}/{}] {} {} — {}",
                            ingested, Math.min(limit, filings.size()),
                            filing.docId(), filing.filerName(), segmentation.stats());
                } catch (EdinetException e) {
                    // One unreadable filing should not end a fifteen-minute run.
                    failed++;
                    log.warn("skipping {} ({}): {}",
                            filing.docId(), filing.filerName(), e.getMessage());
                }
            }
            log.info("ingested={} skipped={} failed={}", ingested, skipped, failed);
            log.info("corpus: {}", store.stats());
        }
    }

    // --- review ---------------------------------------------------------

    /** Loads every headword in the local Anki collection into the known set. */
    private static void anki(Database database) {
        var anki = AnkiConnect.withDefaults();
        if (!anki.isAvailable()) {
            throw new IllegalStateException(
                    "AnkiConnect is not responding — is Anki running with the add-on enabled?");
        }
        var review = new ReviewStore(database);
        review.addKnown(anki.headwords(), "anki");
        log.info("known set now holds {} lemmas", review.knownCount());
    }

    private static void sample(Database database, String[] args) {
        int size = args.length > 0 ? Integer.parseInt(args[0]) : DEFAULT_BATCH_SIZE;
        Path out = args.length > 1 ? Path.of(args[1]) : DEFAULT_SHEET;

        var review = new ReviewStore(database);
        review.populate(MIN_DOCUMENT_FREQUENCY, baselineRanks());

        List<Candidate> batch = review.nextBatch(size);
        write(out, ReviewSheet.write(batch));

        log.info("wrote {} candidates to {}", batch.size(), out);
        ReviewSheet.instructions().forEach(log::info);
    }

    /**
     * General-Japanese ranks, or no ranks at all if the baseline is absent.
     *
     * <p>BCCWJ is free for research and education but not redistributable, so it
     * is downloaded separately. Without it the pipeline still runs; it just loses
     * its only working predictor of what the reviewer does not know.
     */
    private static Function<String, Integer> baselineRanks() {
        if (!Files.exists(BASELINE)) {
            log.warn("baseline not found at {} — candidates will be unranked", BASELINE);
            return lemma -> null;
        }
        Baseline baseline = Baseline.load(BASELINE);
        return lemma -> baseline.rankOf(lemma).orElse(null);
    }

    /** Writes every decided candidate, so the labelled data can be version controlled. */
    private static void export(Database database, String[] args) {
        Path out = args.length > 0 ? Path.of(args[0]) : DEFAULT_SHEET;

        List<Candidate> decided = new ReviewStore(database).decided();
        write(out, ReviewSheet.write(decided));

        log.info("exported {} decided candidates to {}", decided.size(), out);
    }

    private static void verdicts(Database database, String[] args) {
        Path in = args.length > 0 ? Path.of(args[0]) : DEFAULT_SHEET;

        Map<String, CandidateStatus> parsed = ReviewSheet.readVerdicts(read(in));
        int updated = new ReviewStore(database).recordVerdicts(parsed);

        log.info("read {} verdicts from {}, applied {}", parsed.size(), in, updated);
        reportVerdicts(database);
    }

    // --- status ---------------------------------------------------------

    private static void status(Database database) {
        var corpus = new CorpusStore(database);
        log.info("corpus: {}", corpus.stats());
        log.info("top {} terms by document frequency:", TOP_TERMS_REPORTED);
        corpus.topTermsByDocumentFrequency(TOP_TERMS_REPORTED).forEach(term ->
                log.info("  {} docs {} total  {} ({})",
                        "%5d".formatted(term.documentFrequency()),
                        "%7d".formatted(term.corpusFrequency()),
                        term.key(), term.pos()));
        reportVerdicts(database);
    }

    private static void reportVerdicts(Database database) {
        Map<CandidateStatus, Long> counts = new ReviewStore(database).verdictCounts();
        long decided = counts.get(CandidateStatus.KNOWN)
                + counts.get(CandidateStatus.WORTH_LEARNING)
                + counts.get(CandidateStatus.NOT_WORTH_LEARNING);

        log.info("review: {} pending, {} decided", counts.get(CandidateStatus.PENDING), decided);
        if (decided == 0) {
            return;
        }
        long worth = counts.get(CandidateStatus.WORTH_LEARNING);
        log.info("  known {} | worth learning {} | not worth {}",
                counts.get(CandidateStatus.KNOWN), worth,
                counts.get(CandidateStatus.NOT_WORTH_LEARNING));
        log.info("  precision (worth / decided): {}%", 100 * worth / decided);
    }

    // --- io -------------------------------------------------------------

    private static void write(Path path, String content) {
        try {
            Files.writeString(path, content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot write " + path, e);
        }
    }

    private static String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + path, e);
        }
    }
}
