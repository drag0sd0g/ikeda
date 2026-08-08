package com.ikeda.ingest;

public record DocumentFilter(
        String docTypeCode,
        String ordinanceCode,
        String formCode,
        String taxonomyPrefix) {
    public static final DocumentFilter CORPORATE_ANNUAL_REPORT =
            new DocumentFilter("120", "010", "030000", "jpcrp");

    public boolean matches(FilingRef ref) {
        return ref.csvAvailable()
                && docTypeCode.equals(ref.docTypeCode())
                && ordinanceCode.equals(ref.ordinanceCode())
                && formCode.equals(ref.formCode());
    }
}
