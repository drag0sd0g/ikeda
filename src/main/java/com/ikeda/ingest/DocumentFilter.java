package com.ikeda.ingest;

/**
 * Selects filings by genre.
 *
 * <p>{@code docTypeCode} 120 alone is too coarse: it also matches 特定有価証券報告書
 * (investment trusts, SPCs), which use the jpsps taxonomy and a different document
 * structure. The ordinance and form codes narrow this to ordinary corporate filings,
 * and {@code taxonomyPrefix} additionally excludes the jpaud audit reports that are
 * bundled inside every filing.
 */
public record DocumentFilter(
        String docTypeCode,
        String ordinanceCode,
        String formCode,
        String taxonomyPrefix) {

    /** Ordinary corporate 有価証券報告書. */
    public static final DocumentFilter CORPORATE_ANNUAL_REPORT =
            new DocumentFilter("120", "010", "030000", "jpcrp");

    public boolean matches(FilingRef ref) {
        return ref.csvAvailable()
                && docTypeCode.equals(ref.docTypeCode())
                && ordinanceCode.equals(ref.ordinanceCode())
                && formCode.equals(ref.formCode());
    }
}
