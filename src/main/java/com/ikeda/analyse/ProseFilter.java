package com.ikeda.analyse;

public record ProseFilter(int minChars, int maxChars) {
    private static final char SENTENCE_END = '。';

    public static final ProseFilter CORPUS = new ProseFilter(15, 200);

    public boolean isProse(String sentence) {
        String trimmed = sentence.strip();
        return trimmed.length() >= minChars
                && trimmed.length() <= maxChars
                && trimmed.charAt(trimmed.length() - 1) == SENTENCE_END;
    }
}
