package com.ikeda.ingest;

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
