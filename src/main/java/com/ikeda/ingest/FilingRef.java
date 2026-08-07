package com.ikeda.ingest;

/** A single filing as advertised by the EDINET document listing. */
public record FilingRef(
        String docId,
        String edinetCode,
        String filerName,
        String docTypeCode,
        String ordinanceCode,
        String formCode,
        String submitDateTime,
        boolean csvAvailable) {
}
