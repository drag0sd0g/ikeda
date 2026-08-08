package com.ikeda.store;

public record TermFrequency(String key, String pos, long corpusFrequency, long documentFrequency) {
}
