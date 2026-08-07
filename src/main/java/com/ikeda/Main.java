package com.ikeda;

import com.ikeda.analyse.ProseFilter;
import com.ikeda.analyse.Segmenter;
import com.ikeda.ingest.DocumentFilter;
import com.ikeda.ingest.EdinetApi;
import com.ikeda.ingest.EdinetCatalogue;
import com.ikeda.ingest.Extraction;
import com.ikeda.ingest.FilingRef;
import com.ikeda.ingest.NarrativeExtractor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

public class Main {

    private static final Logger log = LoggerFactory.getLogger(Main.class);
    private static final Path SYSTEM_DICTIONARY = Path.of("dict/system_core.dic");

    void main(String[] args) {
        String apiKey = System.getenv("EDINET_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("EDINET_KEY not set");
        }

        LocalDate date = args.length > 0 ? LocalDate.parse(args[0]) : LocalDate.of(2026, 6, 26);
        int limit = args.length > 1 ? Integer.parseInt(args[1]) : 1;
        var filter = DocumentFilter.CORPORATE_ANNUAL_REPORT;

        var api = EdinetApi.withDefaults(apiKey);
        var catalogue = new EdinetCatalogue(api);
        var extractor = new NarrativeExtractor(filter.taxonomyPrefix());

        List<FilingRef> filings = catalogue.filingsOn(date, filter);
        if (filings.isEmpty()) {
            log.warn("no filings matched on {}", date);
            return;
        }

        try (var segmenter = new Segmenter(SYSTEM_DICTIONARY, ProseFilter.CORPUS)) {
            for (FilingRef filing : filings.stream().limit(limit).toList()) {
                Extraction extraction = extractor.extract(api.fetchCsvBundle(filing.docId()));
                var segmentation = segmenter.segment(extraction.blocks());

                log.info("{} {} — {} chars, {}",
                        filing.docId(), filing.filerName(),
                        extraction.totalChars(), segmentation.stats());

                segmentation.sentences().stream().limit(5).forEach(sentence ->
                        log.info("  {}", sentence.text()));
            }
        }
    }
}
