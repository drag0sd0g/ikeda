package com.ikeda.ingest;

/**
 * One narrative section of a filing.
 *
 * <p>The element ID is retained for traceability back to the source section
 * (事業等のリスク, 経営方針, …). It is deliberately <em>not</em> used to filter
 * blocks by genre: the same element holds tables in one filer and dense legal
 * prose in another. Prose selection happens per sentence instead — see TDD §5.0.
 */
public record NarrativeBlock(String elementId, String text) {
}
