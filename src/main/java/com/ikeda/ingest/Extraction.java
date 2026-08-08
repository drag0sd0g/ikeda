package com.ikeda.ingest;

import java.util.List;

/** The result of extracting narrative sections from one filing's CSV bundle. */
public record Extraction(List<NarrativeBlock> blocks, ExtractionStats stats) {
}
