package com.ikeda.analyse;

import java.util.List;

public record AnalysedSentence(
        int blockIndex,
        int seq,
        String elementId,
        String text,
        int tokenCount,
        List<TermOccurrence> terms) {
}
