package com.ikeda.cli;

import com.ikeda.ingest.DocumentFilter;
import com.ikeda.ingest.EdinetApi;
import com.ikeda.ingest.EdinetCatalogue;
import com.ikeda.ingest.EdinetException;
import com.ikeda.ingest.Extraction;
import com.ikeda.ingest.FilingRef;
import com.ikeda.ingest.NarrativeExtractor;
import com.ikeda.store.CorpusStore;
import com.ikeda.store.Database;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.time.LocalDate;
import java.util.List;

@Command(name = "ingest", description = "Fetch, segment and store filings for one date.")
public final class IngestCommand implements Runnable {
    private static final Logger log = LoggerFactory.getLogger(IngestCommand.class);

    @Parameters(index = "0", description = "Filing date, e.g. 2026-06-26.")
    LocalDate date;

    @Option(names = {"-n", "--limit"},
            description = "Maximum filings to newly ingest in this run.")
    int limit = Integer.MAX_VALUE;

    private final Workspace workspace;

    public IngestCommand() {
        this(new Workspace());
    }

    IngestCommand(Workspace workspace) {
        this.workspace = workspace;
    }

    @Override
    public void run() {
        String apiKey = System.getenv("EDINET_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("EDINET_KEY not set");
        }

        var filter = DocumentFilter.CORPORATE_ANNUAL_REPORT;
        var api = EdinetApi.withDefaults(apiKey);
        var extractor = new NarrativeExtractor(filter.taxonomyPrefix());
        List<FilingRef> filings = new EdinetCatalogue(api).filingsOn(date, filter);

        if (filings.isEmpty()) {
            log.warn("no filings matched on {}", date);
            return;
        }

        try (Database database = workspace.openDatabase();
             var segmenter = workspace.openSegmenter()) {
            var store = new CorpusStore(database);
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
                    log.info("[{}/{}] {} {} — {}", ingested, Math.min(limit, filings.size()),
                            filing.docId(), filing.filerName(), segmentation.stats());
                } catch (EdinetException e) {
                    failed++;
                    log.warn("skipping {} ({}): {}",
                            filing.docId(), filing.filerName(), e.getMessage());
                }
            }
            log.info("ingested={} skipped={} failed={}", ingested, skipped, failed);
            log.info("corpus: {}", store.stats());
        }
    }
}
