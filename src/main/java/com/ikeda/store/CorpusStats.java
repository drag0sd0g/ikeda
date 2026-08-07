package com.ikeda.store;

/** Row counts across the corpus, used to verify the phase 1 exit criterion. */
public record CorpusStats(long filings, long blocks, long sentences, long terms, long occurrences) {

    @Override
    public String toString() {
        return "filings=%d blocks=%d sentences=%d terms=%d occurrences=%d"
                .formatted(filings, blocks, sentences, terms, occurrences);
    }
}
