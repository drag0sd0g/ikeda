package com.ikeda.rank;

import java.util.Optional;
import java.util.Set;

public interface BaselineRanking {

    BaselineRanking NONE = new BaselineRanking() {
        @Override
        public Optional<Integer> rankOf(String lemma) {
            return Optional.empty();
        }

        @Override
        public Set<String> commonest(int limit) {
            return Set.of();
        }

        @Override
        public int rarerThanAll() {
            return Integer.MAX_VALUE;
        }
    };

    Optional<Integer> rankOf(String lemma);

    Set<String> commonest(int limit);

    int rarerThanAll();
}
