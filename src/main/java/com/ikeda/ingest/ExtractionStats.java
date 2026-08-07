package com.ikeda.ingest;

/** Per-stage counts for one extraction, so losses are attributable to a stage. */
public record ExtractionStats(
        int zipEntries,
        int csvFiles,
        int rows,
        int textBlockRows,
        int blocks) {

    @Override
    public String toString() {
        return "entries=%d csv=%d rows=%d textBlockRows=%d blocks=%d"
                .formatted(zipEntries, csvFiles, rows, textBlockRows, blocks);
    }
}
