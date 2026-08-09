package com.ikeda.coverage;

import java.util.List;

public record Coverage(
        long knownOccurrences,
        long totalOccurrences,
        long knownTerms,
        long totalTerms,
        List<Milestone> milestones) {

    public record Milestone(double target, long wordsNeeded, boolean reached) { }

    public double tokenCoverage() {
        return totalOccurrences == 0 ? 0 : (double) knownOccurrences / totalOccurrences;
    }

    public double termCoverage() {
        return totalTerms == 0 ? 0 : (double) knownTerms / totalTerms;
    }
}
