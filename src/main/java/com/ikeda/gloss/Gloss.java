package com.ikeda.gloss;

import java.util.List;

public record Gloss(String headword, String reading, List<String> meanings) {

    public String meaningLine() {
        return String.join("; ", meanings);
    }
}
