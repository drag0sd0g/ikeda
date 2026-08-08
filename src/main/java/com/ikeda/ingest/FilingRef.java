package com.ikeda.ingest;

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
