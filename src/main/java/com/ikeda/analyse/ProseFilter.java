package com.ikeda.analyse;

/**
 * Separates prose from flattened financial tables.
 *
 * <p>Roughly a quarter of a filing is tables, and EDINET's XBRL-to-CSV conversion
 * flattens them into the same TextBlock values as narrative, with all markup and
 * cell boundaries already removed. Structure cannot be recovered, only detected.
 *
 * <p>The signal is the sentence terminator: tables have none, because they are not
 * sentences. This is a structural property of the data rather than a tuned
 * heuristic, and it is the same rule card generation needs for example sentences.
 *
 * <p>Genre filtering by element ID was evaluated and rejected — element IDs
 * describe where content lives, not what it is. See TDD §5.0.
 */
public record ProseFilter(int minChars, int maxChars) {

    private static final char SENTENCE_END = '。';

    /**
     * Corpus ingestion. The upper bound is generous: unusually long sentences are
     * still valid vocabulary evidence even when unusable as card examples.
     */
    public static final ProseFilter CORPUS = new ProseFilter(15, 200);

    public boolean isProse(String sentence) {
        String trimmed = sentence.strip();
        return trimmed.length() >= minChars
                && trimmed.length() <= maxChars
                && trimmed.charAt(trimmed.length() - 1) == SENTENCE_END;
    }
}
