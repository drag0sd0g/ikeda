package com.ikeda.store;

public record CorpusStats(long filings, long blocks, long sentences, long terms, long occurrences) {
    @Override
    public String toString() {
        return "filings=%d blocks=%d sentences=%d terms=%d occurrences=%d"
                .formatted(filings, blocks, sentences, terms, occurrences);
    }
}
