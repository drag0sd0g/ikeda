package com.ikeda.ingest;

import com.ikeda.support.Encodings;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Extracts narrative sections from an EDINET XBRL-to-CSV bundle.
 *
 * <p>Pure with respect to I/O: it takes the bundle as bytes, so the whole parsing
 * path is unit-testable without a network call or an API key.
 */
public final class NarrativeExtractor {

    private static final Logger log = LoggerFactory.getLogger(NarrativeExtractor.class);

    private static final String COLUMN_ELEMENT_ID = "要素ID";
    private static final String COLUMN_VALUE = "値";
    private static final String TEXT_BLOCK_MARKER = "TextBlock";
    private static final String CSV_SUFFIX = ".csv";

    private static final Pattern HTML_TAG = Pattern.compile("<[^>]+>");
    private static final Pattern WHITESPACE_RUN = Pattern.compile("\\s+");

    private static final CSVFormat EDINET_CSV = CSVFormat.DEFAULT.builder()
            .setDelimiter('\t')
            .setQuote('"')
            .setHeader()
            .setSkipHeaderRecord(true)
            .get();

    private final String taxonomyPrefix;

    public NarrativeExtractor(String taxonomyPrefix) {
        this.taxonomyPrefix = taxonomyPrefix;
    }

    public Extraction extract(byte[] zipBytes) {
        var blocks = new ArrayList<NarrativeBlock>();
        int zipEntries = 0;
        int csvFiles = 0;
        int rows = 0;
        int textBlockRows = 0;

        try (var zip = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            for (ZipEntry entry; (entry = zip.getNextEntry()) != null; ) {
                zipEntries++;
                if (!isWanted(entry.getName())) {
                    log.debug("skipping {}", entry.getName());
                    continue;
                }
                csvFiles++;
                log.debug("parsing {}", entry.getName());

                String csv = Encodings.decode(zip.readAllBytes());
                try (var parser = CSVParser.parse(new StringReader(csv), EDINET_CSV)) {
                    for (CSVRecord record : parser) {
                        rows++;
                        if (!record.isMapped(COLUMN_ELEMENT_ID) || !record.isMapped(COLUMN_VALUE)) {
                            continue;
                        }
                        String elementId = record.get(COLUMN_ELEMENT_ID);
                        if (!elementId.contains(TEXT_BLOCK_MARKER)) {
                            continue;
                        }
                        textBlockRows++;
                        String text = stripHtml(record.get(COLUMN_VALUE));
                        if (!text.isBlank()) {
                            blocks.add(new NarrativeBlock(elementId, text));
                        }
                    }
                }
            }
        } catch (IOException e) {
            throw new EdinetException("failed to read CSV bundle", e);
        }

        var stats = new ExtractionStats(zipEntries, csvFiles, rows, textBlockRows, blocks.size());
        log.debug("extraction complete: {}", stats);
        return new Extraction(List.copyOf(blocks), stats);
    }

    private boolean isWanted(String entryName) {
        return entryName.toLowerCase().endsWith(CSV_SUFFIX)
                && entryName.contains("/" + taxonomyPrefix);
    }

    private static String stripHtml(String raw) {
        String withoutTags = HTML_TAG.matcher(raw).replaceAll(" ");
        String withoutEntities = withoutTags.replace("&nbsp;", " ");
        return WHITESPACE_RUN.matcher(withoutEntities).replaceAll(" ").trim();
    }
}
