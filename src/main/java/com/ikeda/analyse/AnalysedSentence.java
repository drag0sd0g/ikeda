package com.ikeda.analyse;

import java.util.List;

/**
 * A prose sentence ready to persist, with its content words already extracted.
 *
 * @param blockIndex index of the source block within the filing, so the store can
 *                   resolve the block's generated id
 * @param seq        position of this sentence within its block; gaps are expected,
 *                   because non-prose segments and duplicates are dropped first
 * @param elementId  source XBRL element, retained for traceability
 * @param tokenCount all morphemes, not only content words
 */
public record AnalysedSentence(
        int blockIndex,
        int seq,
        String elementId,
        String text,
        int tokenCount,
        List<TermOccurrence> terms) {
}
