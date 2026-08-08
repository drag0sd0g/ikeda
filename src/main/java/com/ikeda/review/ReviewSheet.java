package com.ikeda.review;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ReviewSheet {
    static final String VERDICT_COLUMN = "verdict";
    private static final String TERM_COLUMN = "term";

    private static final List<String> COLUMNS =
            List.of(VERDICT_COLUMN, TERM_COLUMN, "reading", "pos", "docs", "total", "example");

    private static final String SEPARATOR = "\t";

    private ReviewSheet() {
    }

    public static String write(List<Candidate> candidates) {
        var out = new StringBuilder(String.join(SEPARATOR, COLUMNS)).append('\n');
        for (Candidate candidate : candidates) {
            out.append(String.join(SEPARATOR,
                            verdictCell(candidate.status()),
                            clean(candidate.key()),
                            clean(candidate.reading()),
                            clean(candidate.pos()),
                            String.valueOf(candidate.documentFrequency()),
                            String.valueOf(candidate.corpusFrequency()),
                            clean(candidate.example())))
                    .append('\n');
        }
        return out.toString();
    }

    public static Map<String, CandidateStatus> readVerdicts(String tsv) {
        List<String> lines = tsv.lines().filter(line -> !line.isBlank()).toList();
        if (lines.isEmpty()) {
            return Map.of();
        }

        List<String> header = List.of(lines.getFirst().split(SEPARATOR, -1));
        int verdictColumn = header.indexOf(VERDICT_COLUMN);
        int termColumn = header.indexOf(TERM_COLUMN);
        if (verdictColumn < 0 || termColumn < 0) {
            throw new IllegalArgumentException(
                    "sheet must have '%s' and '%s' columns, found %s"
                            .formatted(VERDICT_COLUMN, TERM_COLUMN, header));
        }

        var verdicts = new LinkedHashMap<String, CandidateStatus>();
        for (String line : lines.subList(1, lines.size())) {
            String[] cells = line.split(SEPARATOR, -1);
            if (cells.length <= Math.max(verdictColumn, termColumn)) {
                continue;
            }
            String term = cells[termColumn].strip();
            if (term.isEmpty()) {
                continue;
            }
            CandidateStatus.parse(cells[verdictColumn])
                    .filter(status -> status != CandidateStatus.PENDING)
                    .ifPresent(status -> verdicts.put(term, status));
        }
        return verdicts;
    }

    private static String verdictCell(CandidateStatus status) {
        return switch (status == null ? CandidateStatus.PENDING : status) {
            case KNOWN -> "k";
            case WORTH_LEARNING -> "w";
            case NOT_WORTH_LEARNING -> "n";
            case PENDING -> "";
        };
    }

    private static String clean(String value) {
        return value == null ? "" : value.replace('\t', ' ').replace('\n', ' ').strip();
    }

    public static List<String> instructions() {
        var lines = new ArrayList<String>();
        lines.add("Fill the 'verdict' column for each row:");
        lines.add("  k = I already know this word");
        lines.add("  w = new to me, and worth a card");
        lines.add("  n = new to me, but not worth a card");
        lines.add("Leave blank to skip. Save, then import it back with the verdicts command.");
        return lines;
    }
}
