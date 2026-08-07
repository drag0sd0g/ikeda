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
import com.ikeda.store.CorpusStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

/**
 * Phase 1 corpus ingestion.
 *
 * <p>Usage: {@code run --args="<date> [limit]"}, for example
 * {@code --args="2026-06-26 181"}.
 *
 * <p>Safe to interrupt and re-run: filings already stored are skipped. The limit
 * bounds how many filings this run <em>newly</em> ingests, not the total in the
 * store, so repeated small runs accumulate and an interrupted full run resumes
 * where it stopped.
 */
public class Main {

    private static final Logger log = LoggerFactory.getLogger(Main.class);

    private static final Path SYSTEM_DICTIONARY = Path.of("dict/system_core.dic");
    private static final Path DATABASE = Path.of("ikeda.db");
    private static final int TOP_TERMS_REPORTED = 20;

    void main(String[] args) {
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

        List<FilingRef> filings = catalogue.filingsOn(date, filter);
        if (filings.isEmpty()) {
            log.warn("no filings matched on {}", date);
            return;
        }

        try (var store = CorpusStore.open(DATABASE);
             var segmenter = new Segmenter(SYSTEM_DICTIONARY, ProseFilter.CORPUS)) {

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
            report(store);
        }
    }

    private static void report(CorpusStore store) {
        log.info("corpus: {}", store.stats());
        log.info("top {} terms by document frequency:", TOP_TERMS_REPORTED);
        store.topTermsByDocumentFrequency(TOP_TERMS_REPORTED).forEach(term ->
                log.info("  {} docs {} total  {} ({})",
                        "%5d".formatted(term.documentFrequency()),
                        "%7d".formatted(term.corpusFrequency()),
                        term.key(), term.pos()));
    }
}
