package com.ikeda.analyse;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

public final class ExampleSelector {

    private static final Pattern HEADING_PREFIX =
            Pattern.compile("^(?:[０-９0-9.\\s]*[【（(][^】）)]*[】）)]\\s*)+");

    private static final Set<String> OPENING_ANAPHORA =
            Set.of("当該", "上記", "同社", "同", "なお", "また", "これら", "その", "本");

    private final int minChars;
    private final int maxChars;

    public ExampleSelector(int minChars, int maxChars) {
        this.minChars = minChars;
        this.maxChars = maxChars;
    }

    public static ExampleSelector forCards() {
        return new ExampleSelector(15, 80);
    }

    public record SentenceContext(long sentenceId, String text, List<String> terms) { }

    public Optional<SentenceContext> select(String target,
                                            List<SentenceContext> sentences,
                                            Set<String> known) {
        return sentences.stream()
                .map(ExampleSelector::withHeadingStripped)
                .filter(sentence -> sentence.text().contains(target))
                .filter(this::withinLengthBounds)
                .filter(ExampleSelector::withoutOpeningAnaphora)
                .min(Comparator
                        .comparingInt((SentenceContext s) -> unknownCount(s, target, known))
                        .thenComparingInt(s -> s.text().length()));
    }

    public static String stripHeading(String text) {
        return HEADING_PREFIX.matcher(text).replaceFirst("").strip();
    }

    public static int unknownCount(SentenceContext sentence, String target, Set<String> known) {
        return (int) sentence.terms().stream()
                .filter(term -> !term.equals(target))
                .filter(term -> !known.contains(term))
                .distinct()
                .count();
    }

    private static SentenceContext withHeadingStripped(SentenceContext sentence) {
        String stripped = stripHeading(sentence.text());
        return stripped.equals(sentence.text())
                ? sentence
                : new SentenceContext(sentence.sentenceId(), stripped, sentence.terms());
    }

    private boolean withinLengthBounds(SentenceContext sentence) {
        int length = sentence.text().length();
        return length >= minChars && length <= maxChars;
    }

    private static boolean withoutOpeningAnaphora(SentenceContext sentence) {
        return OPENING_ANAPHORA.stream().noneMatch(sentence.text()::startsWith);
    }
}
