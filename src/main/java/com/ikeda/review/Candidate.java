package com.ikeda.review;

/**
 * A term put in front of the reviewer, with the evidence needed to judge it.
 *
 * @param corpusFrequency   total occurrences across the corpus
 * @param documentFrequency filings the term appears in
 * @param example           a real sentence from a real filing, or null if none fit
 */
public record Candidate(
        long termId,
        String key,
        String reading,
        String pos,
        long corpusFrequency,
        long documentFrequency,
        String example,
        CandidateStatus status) {
}
