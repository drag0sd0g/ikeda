package com.ikeda.ingest;

import java.util.List;

public record Extraction(List<NarrativeBlock> blocks, ExtractionStats stats) {
}
