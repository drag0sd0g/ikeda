package com.ikeda.review;

import java.util.Locale;
import java.util.Optional;

/**
 * A reviewer's verdict on a candidate word.
 *
 * <p>Three outcomes rather than two, because "I already know it" and "not worth a
 * card" mean different things. The first calibrates the known-set model; the
 * second is the phase 2 exit measurement.
 */
public enum CandidateStatus {

    /** Not yet reviewed. */
    PENDING,

    /** Already known, so it should never have been proposed. Calibration signal. */
    KNOWN,

    /** Unknown and worth a card. Counts towards the exit criterion. */
    WORTH_LEARNING,

    /** Unknown but not worth a card — too rare, too technical, a proper noun. */
    NOT_WORTH_LEARNING;

    /**
     * Parses a cell from a review sheet, forgiving about how it was typed.
     *
     * <p>Accepts the full name, or the single letters k / w / n. Blank means the
     * row was left alone, which is not a verdict.
     */
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
