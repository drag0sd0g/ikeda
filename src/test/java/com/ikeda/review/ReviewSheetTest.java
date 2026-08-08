package com.ikeda.review;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReviewSheetTest {

    @Test
    @DisplayName("writes a header and one row per candidate")
    void writesRows() {
        String sheet = ReviewSheet.write(List.of(
                candidate("蓋然性", 68, 412, "将来の課税所得が生じる蓋然性を勘案しております。"),
                candidate("毀損", 74, 388, "企業価値が毀損するおそれがあります。")));

        List<String> lines = sheet.lines().toList();

        assertThat(lines).hasSize(3);
        assertThat(lines.getFirst()).startsWith("verdict\tterm\treading\tpos\tdocs\ttotal\texample");
        assertThat(lines.get(1)).contains("蓋然性").contains("68").contains("412");
    }

    @Test
    @DisplayName("leaves the verdict column empty for undecided candidates")
    void leavesVerdictEmpty() {
        String sheet = ReviewSheet.write(List.of(candidate("蓋然性", 68, 412, "例文です。")));

        assertThat(sheet.lines().skip(1).findFirst().orElseThrow()).startsWith("\t蓋然性");
    }

    @Test
    @DisplayName("round trips a decided candidate back to the same verdict")
    void roundTrips() {
        var decided = new Candidate(1L, "蓋然性", "ガイゼンセイ", "名詞", 412, 68,
                "例文です。", CandidateStatus.WORTH_LEARNING);

        Map<String, CandidateStatus> verdicts =
                ReviewSheet.readVerdicts(ReviewSheet.write(List.of(decided)));

        assertThat(verdicts).containsExactly(
                Map.entry("蓋然性", CandidateStatus.WORTH_LEARNING));
    }

    @Test
    @DisplayName("accepts short codes and full names, in any case")
    void acceptsVariedSpellings() {
        String sheet = """
                verdict\tterm\treading\tpos\tdocs\ttotal\texample
                k\t当社\tトウシャ\t名詞\t181\t37698\t例文です。
                W\t蓋然性\tガイゼンセイ\t名詞\t68\t412\t例文です。
                not_worth_learning\t公司\tコンス\t名詞\t20\t95\t例文です。
                """;

        assertThat(ReviewSheet.readVerdicts(sheet)).containsExactly(
                Map.entry("当社", CandidateStatus.KNOWN),
                Map.entry("蓋然性", CandidateStatus.WORTH_LEARNING),
                Map.entry("公司", CandidateStatus.NOT_WORTH_LEARNING));
    }

    @Test
    @DisplayName("skips blank verdicts, so a partly reviewed sheet imports cleanly")
    void skipsBlankVerdicts() {
        String sheet = """
                verdict\tterm\treading\tpos\tdocs\ttotal\texample
                k\t当社\tトウシャ\t名詞\t181\t37698\t例文です。
                \t蓋然性\tガイゼンセイ\t名詞\t68\t412\t例文です。
                """;

        assertThat(ReviewSheet.readVerdicts(sheet))
                .containsExactly(Map.entry("当社", CandidateStatus.KNOWN));
    }

    @Test
    @DisplayName("tolerates reordered columns")
    void toleratesReorderedColumns() {
        String sheet = """
                term\texample\tverdict
                蓋然性\t例文です。\tw
                """;

        assertThat(ReviewSheet.readVerdicts(sheet))
                .containsExactly(Map.entry("蓋然性", CandidateStatus.WORTH_LEARNING));
    }

    @Test
    @DisplayName("rejects a sheet missing the columns it needs")
    void rejectsMissingColumns() {
        assertThatThrownBy(() -> ReviewSheet.readVerdicts("term\texample\n蓋然性\t例文です。\n"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("verdict");
    }

    @Test
    @DisplayName("rejects a verdict it cannot interpret rather than guessing")
    void rejectsUnknownVerdict() {
        String sheet = "verdict\tterm\nmaybe\t蓋然性\n";

        assertThatThrownBy(() -> ReviewSheet.readVerdicts(sheet))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maybe");
    }

    @Test
    @DisplayName("strips tabs from example sentences so rows cannot be split apart")
    void stripsTabsFromExamples() {
        String sheet = ReviewSheet.write(List.of(
                candidate("蓋然性", 68, 412, "前半\tと\t後半。")));

        assertThat(sheet.lines().skip(1).findFirst().orElseThrow().split("\t"))
                .hasSize(7);
    }

    @Test
    @DisplayName("handles a candidate with no example sentence")
    void handlesMissingExample() {
        var withoutExample = new Candidate(1L, "蓋然性", "ガイゼンセイ", "名詞", 412, 68,
                null, CandidateStatus.PENDING);

        assertThat(ReviewSheet.write(List.of(withoutExample))).contains("蓋然性");
    }

    @Test
    @DisplayName("an empty sheet yields no verdicts")
    void handlesEmptySheet() {
        assertThat(ReviewSheet.readVerdicts("")).isEmpty();
        assertThat(ReviewSheet.readVerdicts("verdict\tterm\n")).isEmpty();
    }

    private static Candidate candidate(String key, long df, long cf, String example) {
        return new Candidate(1L, key, "カナ", "名詞", cf, df, example, CandidateStatus.PENDING);
    }
}
