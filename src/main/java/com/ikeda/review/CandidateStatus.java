package com.ikeda.review;

import java.util.Locale;
import java.util.Optional;

public enum CandidateStatus {
    PENDING,

    KNOWN,

    WORTH_LEARNING,

    NOT_WORTH_LEARNING;

    public static Optional<CandidateStatus> parse(String cell) {
        String value = cell == null ? "" : cell.strip().toLowerCase(Locale.ROOT);
        return switch (value) {
            case "" -> Optional.empty();
            case "k", "known" -> Optional.of(KNOWN);
            case "w", "worth", "worth_learning" -> Optional.of(WORTH_LEARNING);
            case "n", "no", "not_worth_learning" -> Optional.of(NOT_WORTH_LEARNING);
            case "p", "pending" -> Optional.of(PENDING);
            default -> throw new IllegalArgumentException(
                    "unrecognised verdict '%s' — use k, w or n".formatted(cell));
        };
    }
}
