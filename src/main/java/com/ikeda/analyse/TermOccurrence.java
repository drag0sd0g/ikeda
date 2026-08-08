package com.ikeda.analyse;

public record TermOccurrence(
        String key,
        String surface,
        String reading,
        String pos,
        int position) {
}
