package com.ikeda.store;

/**
 * Raw corpus counts for one term.
 *
 * @param corpusFrequency   total occurrences across the corpus
 * @param documentFrequency number of distinct filings the term appears in
 */
public record TermFrequency(String key, String pos, long corpusFrequency, long documentFrequency) {
}
