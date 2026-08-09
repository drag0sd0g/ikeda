package com.ikeda.ui;

import java.util.List;

public record ReviewItem(
        String term,
        String reading,
        String meaning,
        String partOfSpeech,
        List<String> parts,
        long documentFrequency,
        long corpusFrequency,
        Integer baselineRank,
        List<Example> examples) {

    public record Example(String text, String source) { }
}
