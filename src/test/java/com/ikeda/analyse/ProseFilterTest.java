package com.ikeda.analyse;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ProseFilterTest {

    private final ProseFilter filter = ProseFilter.CORPUS;

    @Test
    @DisplayName("keeps narrative prose")
    void keepsProse() {
        assertThat(filter.isProse(
                "当社グループは、鉄軌道事業や旅客自動車運送事業などを営んでおります。")).isTrue();
    }

    @Test
    @DisplayName("keeps prose found inside table blocks, which is why blocks are not filtered")
    void keepsProseFromTableBlocks() {
        // Both observed inside element IDs that read like pure tables.
        assertThat(filter.isProse(
                "なお、Ａ種優先株式の一部を取得するときは、抽選、比例按分その他の方法により決定する。")).isTrue();
        assertThat(filter.isProse(
                "「減価償却累計額」欄には、減損損失累計額が含まれております。")).isTrue();
    }

    @Test
    @DisplayName("rejects flattened financial tables, which carry no sentence terminator")
    void rejectsFlattenedTables() {
        assertThat(filter.isProse(
                "売上高（千円）3,054,7143,364,9353,293,3673,797,3743,571,516")).isFalse();
        assertThat(filter.isProse(
                "回次第107期第108期第109期第110期第111期決算年月令和４年３月")).isFalse();
    }

    @Test
    @DisplayName("rejects headings, which lack a terminator")
    void rejectsHeadings() {
        assertThat(filter.isProse("３【事業等のリスク】")).isFalse();
        assertThat(filter.isProse("２【沿革】")).isFalse();
    }

    @Test
    @DisplayName("rejects sentences outside the length bounds")
    void rejectsOutOfBounds() {
        assertThat(filter.isProse("該当なし。")).isFalse();
        assertThat(filter.isProse("あ".repeat(200) + "。")).isFalse();
    }

    @Test
    @DisplayName("accepts at both bounds")
    void acceptsAtBounds() {
        assertThat(filter.isProse("あ".repeat(14) + "。")).isTrue();     // 15
        assertThat(filter.isProse("あ".repeat(199) + "。")).isTrue();    // 200
    }

    @Test
    @DisplayName("tolerates surrounding whitespace")
    void tolerRatesWhitespace() {
        assertThat(filter.isProse("　　当社は次のとおり定めております。　 ")).isTrue();
    }

    @Test
    @DisplayName("rejects blank and empty input")
    void rejectsBlank() {
        assertThat(filter.isProse("")).isFalse();
        assertThat(filter.isProse("    ")).isFalse();
    }
}
