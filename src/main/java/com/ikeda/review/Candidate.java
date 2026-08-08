package com.ikeda.review;

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
