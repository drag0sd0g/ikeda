package com.ikeda.ingest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** Lists filings and selects them by genre. */
public final class EdinetCatalogue {

    private static final Logger log = LoggerFactory.getLogger(EdinetCatalogue.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final EdinetApi api;

    public EdinetCatalogue(EdinetApi api) {
        this.api = api;
    }

    public List<FilingRef> filingsOn(LocalDate date, DocumentFilter filter) {
        List<FilingRef> all = parse(api.listDocuments(date));
        List<FilingRef> selected = all.stream().filter(filter::matches).toList();
        log.info("{}: {} documents listed, {} match {}",
                date, all.size(), selected.size(), filter.formCode());
        return selected;
    }

    /** Parses a listing response. Package-private and pure, so it is testable from a fixture. */
    static List<FilingRef> parse(byte[] json) {
        try {
            JsonNode root = MAPPER.readTree(json);
            var refs = new ArrayList<FilingRef>();
            for (JsonNode doc : root.path("results")) {
                refs.add(new FilingRef(
                        doc.path("docID").asText(),
                        doc.path("edinetCode").asText(),
                        doc.path("filerName").asText(),
                        doc.path("docTypeCode").asText(),
                        doc.path("ordinanceCode").asText(),
                        doc.path("formCode").asText(),
                        doc.path("submitDateTime").asText(),
                        "1".equals(doc.path("csvFlag").asText())));
            }
            return List.copyOf(refs);
        } catch (IOException e) {
            throw new EdinetException("malformed EDINET listing response", e);
        }
    }
}
