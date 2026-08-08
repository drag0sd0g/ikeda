package com.ikeda.rank;

import java.util.Optional;

@FunctionalInterface
public interface BaselineRanking {
    BaselineRanking NONE = lemma -> Optional.empty();

    Optional<Integer> rankOf(String lemma);
}
