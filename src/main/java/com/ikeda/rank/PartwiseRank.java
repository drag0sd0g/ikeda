package com.ikeda.rank;

import java.util.List;
import java.util.Optional;

public final class PartwiseRank {

    private final BaselineRanking baseline;

    public PartwiseRank(BaselineRanking baseline) {
        this.baseline = baseline;
    }

    public Optional<Integer> estimate(List<String> shortUnits) {
        return shortUnits.stream()
                .map(baseline::rankOf)
                .flatMap(Optional::stream)
                .max(Integer::compare);
    }
}
