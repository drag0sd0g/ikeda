package com.ikeda.compound;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AssociationTest {

    @Test
    @DisplayName("scores parts that always occur together above parts that rarely do")
    void ranksBondedPartsHigher() {
        var association = new Association(
                Map.of("繰延", 100L, "税金", 100L, "当社", 1000L, "拡大", 1000L),
                Map.of(Association.pairKey("繰延", "税金"), 100L,
                        Association.pairKey("当社", "拡大"), 5L));

        assertThat(association.pointwiseMutualInformation("繰延", "税金"))
                .isGreaterThan(association.pointwiseMutualInformation("当社", "拡大"));
    }

    @Test
    @DisplayName("reports negative infinity for a pair never seen together")
    void unseenPairIsImpossible() {
        var association = new Association(
                Map.of("為替", 10L, "拡大", 10L),
                Map.of(Association.pairKey("為替", "変動"), 5L));

        assertThat(association.pointwiseMutualInformation("為替", "拡大"))
                .isEqualTo(Double.NEGATIVE_INFINITY);
    }

    @Test
    @DisplayName("a run is only as strong as its weakest join")
    void weakestLinkGovernsTheRun() {
        var association = new Association(
                Map.of("繰延", 100L, "税金", 100L, "資産", 100L),
                Map.of(Association.pairKey("繰延", "税金"), 100L,
                        Association.pairKey("税金", "資産"), 10L));

        double weakest = association.weakestLink(List.of("繰延", "税金", "資産"));

        assertThat(weakest).isEqualTo(association.pointwiseMutualInformation("税金", "資産"));
        assertThat(weakest).isLessThan(association.pointwiseMutualInformation("繰延", "税金"));
    }

    @Test
    @DisplayName("handles an empty corpus without dividing by zero")
    void handlesEmptyCorpus() {
        var association = new Association(Map.of(), Map.of());

        assertThat(association.pointwiseMutualInformation("為替", "変動"))
                .isEqualTo(Double.NEGATIVE_INFINITY);
    }
}
