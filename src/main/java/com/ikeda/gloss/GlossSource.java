package com.ikeda.gloss;

import java.util.Optional;

@FunctionalInterface
public interface GlossSource {

    GlossSource NONE = headword -> Optional.empty();

    Optional<Gloss> lookup(String headword);
}
