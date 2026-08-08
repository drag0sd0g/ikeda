package com.ikeda.analyse;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

public enum PartOfSpeech {
    NOUN("名詞"),
    VERB("動詞"),
    ADJECTIVE("形容詞"),
    ADVERB("副詞");

    private static final Set<String> LABELS =
            Arrays.stream(values()).map(PartOfSpeech::label).collect(Collectors.toUnmodifiableSet());

    private final String label;

    PartOfSpeech(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    public static boolean isContent(String label) {
        return LABELS.contains(label);
    }

    public static String sqlInList() {
        return Arrays.stream(values())
                .map(pos -> "'" + pos.label + "'")
                .collect(Collectors.joining(", ", "(", ")"));
    }
}
