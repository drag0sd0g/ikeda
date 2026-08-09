package com.ikeda.coverage;

import java.util.ArrayList;
import java.util.List;

public final class CoverageCalculator {

    public record TermFrequency(String key, long occurrences, boolean known) { }

    private final List<Double> targets;

    public CoverageCalculator(List<Double> targets) {
        this.targets = List.copyOf(targets);
    }

    public static CoverageCalculator standard() {
        return new CoverageCalculator(List.of(0.90, 0.95, 0.98));
    }

    public Coverage of(List<TermFrequency> terms) {
        long total = terms.stream().mapToLong(TermFrequency::occurrences).sum();
        long known = terms.stream().filter(TermFrequency::known)
                .mapToLong(TermFrequency::occurrences).sum();
        long knownTerms = terms.stream().filter(TermFrequency::known).count();

        List<TermFrequency> unknownByYield = terms.stream()
                .filter(term -> !term.known())
                .sorted((a, b) -> Long.compare(b.occurrences(), a.occurrences()))
                .toList();

        var milestones = new ArrayList<Coverage.Milestone>();
        for (double target : targets) {
            milestones.add(milestone(target, known, total, unknownByYield));
        }
        return new Coverage(known, total, knownTerms, terms.size(), List.copyOf(milestones));
    }

    private static Coverage.Milestone milestone(double target, long known, long total,
                                                List<TermFrequency> unknownByYield) {
        if (total == 0) {
            return new Coverage.Milestone(target, 0, false);
        }
        long needed = (long) Math.ceil(target * total);
        if (known >= needed) {
            return new Coverage.Milestone(target, 0, true);
        }
        long running = known;
        long words = 0;
        for (TermFrequency term : unknownByYield) {
            running += term.occurrences();
            words++;
            if (running >= needed) {
                return new Coverage.Milestone(target, words, true);
            }
        }
        return new Coverage.Milestone(target, words, false);
    }
}
