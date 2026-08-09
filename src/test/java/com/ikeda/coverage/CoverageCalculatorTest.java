package com.ikeda.coverage;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CoverageCalculatorTest {

    private final CoverageCalculator calculator = CoverageCalculator.standard();

    private static CoverageCalculator.TermFrequency known(String key, long occurrences) {
        return new CoverageCalculator.TermFrequency(key, occurrences, true);
    }

    private static CoverageCalculator.TermFrequency unknown(String key, long occurrences) {
        return new CoverageCalculator.TermFrequency(key, occurrences, false);
    }

    @Test
    @DisplayName("counts occurrences, not distinct words, because reading is done token by token")
    void countsOccurrences() {
        Coverage coverage = calculator.of(List.of(known("当社", 90), unknown("余資", 10)));

        assertThat(coverage.tokenCoverage()).isEqualTo(0.90);
        assertThat(coverage.termCoverage()).isEqualTo(0.50);
    }

    @Test
    @DisplayName("reports how many more words each milestone needs")
    void reportsWordsNeededPerMilestone() {
        Coverage coverage = calculator.of(List.of(
                known("当社", 900),
                unknown("余資", 60),
                unknown("戻入", 30),
                unknown("末残", 10)));

        assertThat(coverage.tokenCoverage()).isEqualTo(0.90);
        assertThat(coverage.milestones())
                .filteredOn(m -> m.target() == 0.95)
                .singleElement()
                .satisfies(m -> {
                    assertThat(m.reached()).isTrue();
                    assertThat(m.wordsNeeded()).isEqualTo(1);
                });
    }

    @Test
    @DisplayName("takes the most frequent unknown words first, since they buy the most")
    void spendsOnTheMostFrequentUnknowns() {
        Coverage coverage = calculator.of(List.of(
                known("当社", 800),
                unknown("rare", 1),
                unknown("common", 199)));

        assertThat(coverage.milestones())
                .filteredOn(m -> m.target() == 0.98)
                .singleElement()
                .satisfies(m -> assertThat(m.wordsNeeded()).isEqualTo(1));
    }

    @Test
    @DisplayName("reports a milestone already reached as needing nothing")
    void alreadyReachedNeedsNothing() {
        Coverage coverage = calculator.of(List.of(known("当社", 99), unknown("余資", 1)));

        assertThat(coverage.milestones())
                .filteredOn(m -> m.target() == 0.90)
                .singleElement()
                .satisfies(m -> {
                    assertThat(m.reached()).isTrue();
                    assertThat(m.wordsNeeded()).isZero();
                });
    }

    @Test
    @DisplayName("marks a milestone unreachable when the corpus cannot supply it")
    void unreachableMilestoneIsReported() {
        Coverage coverage = calculator.of(List.of(unknown("余資", 100)));

        assertThat(coverage.milestones())
                .filteredOn(m -> m.target() == 0.98)
                .singleElement()
                .satisfies(m -> assertThat(m.reached()).isTrue());
        assertThat(coverage.tokenCoverage()).isZero();
    }

    @Test
    @DisplayName("handles an empty corpus without dividing by zero")
    void handlesEmptyCorpus() {
        Coverage coverage = calculator.of(List.of());

        assertThat(coverage.tokenCoverage()).isZero();
        assertThat(coverage.termCoverage()).isZero();
        assertThat(coverage.milestones()).allSatisfy(m -> assertThat(m.wordsNeeded()).isZero());
    }

    @Test
    @DisplayName("full knowledge reaches every milestone with nothing left to learn")
    void fullKnowledgeNeedsNothing() {
        Coverage coverage = calculator.of(List.of(known("当社", 50), known("事業", 50)));

        assertThat(coverage.tokenCoverage()).isEqualTo(1.0);
        assertThat(coverage.milestones())
                .allSatisfy(m -> assertThat(m.wordsNeeded()).isZero());
    }
}
