package com.ikeda.rank;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class PartwiseRankTest {

    private static final int BEYOND_THE_BASELINE = 173_138;

    private static BaselineRanking baseline(Map<String, Integer> ranks) {
        return new BaselineRanking() {
            @Override
            public Optional<Integer> rankOf(String lemma) {
                return Optional.ofNullable(ranks.get(lemma));
            }

            @Override
            public Set<String> commonest(int limit) {
                return Set.of();
            }

            @Override
            public int rarerThanAll() {
                return BEYOND_THE_BASELINE;
            }
        };
    }

    @Test
    @DisplayName("takes the rarest part, because that is what makes the compound hard")
    void rarestPartGoverns() {
        var partwise = new PartwiseRank(baseline(Map.of("税金", 3002, "資産", 1037, "繰延", 16471)));

        assertThat(partwise.estimate(List.of("繰延", "税金", "資産")))
                .contains(16471);
    }

    @Test
    @DisplayName("a compound of common parts stays common, so it does not crowd the queue")
    void transparentCompoundStaysCommon() {
        var partwise = new PartwiseRank(baseline(Map.of("利子", 900, "負債", 4580)));

        assertThat(partwise.estimate(List.of("利子", "負債"))).contains(4580);
    }

    @Test
    @DisplayName("looks parts up at short-unit granularity, matching the baseline")
    void looksUpShortUnits() {
        var partwise = new PartwiseRank(baseline(Map.of("繰り延べ", 16471, "税金", 3002)));

        assertThat(partwise.estimate(List.of("繰り延べ", "税金")))
                .contains(16471);
    }

    @Test
    @DisplayName("ignores a unit the baseline does not contain, rather than calling it rare")
    void absentUnitIsIgnored() {
        var partwise = new PartwiseRank(baseline(Map.of("事業", 178)));

        assertThat(partwise.estimate(List.of("不動産", "事業"))).contains(178);
    }

    @Test
    @DisplayName("has no estimate when no unit can be matched at all")
    void noMatchedUnitsMeansNoEstimate() {
        var partwise = new PartwiseRank(baseline(Map.of()));

        assertThat(partwise.estimate(List.of("貸倒", "引当金"))).isEmpty();
    }

    @Test
    @DisplayName("takes the rarest of many short units")
    void takesRarestShortUnit() {
        var partwise = new PartwiseRank(baseline(Map.of("税金", 3002, "資産", 1037)));

        assertThat(partwise.estimate(List.of("税金", "資産"))).contains(3002);
    }

    @Test
    @DisplayName("has nothing to say about a compound with no parts")
    void noPartsMeansNoEstimate() {
        assertThat(new PartwiseRank(baseline(Map.of())).estimate(List.of())).isEmpty();
    }

    @Test
    @DisplayName("estimates nothing when there is no baseline at all")
    void noBaselineMeansNoEstimate() {
        assertThat(new PartwiseRank(BaselineRanking.NONE).estimate(List.of("繰延", "税金")))
                .isEmpty();
    }
}
