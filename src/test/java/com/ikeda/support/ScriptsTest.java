package com.ikeda.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class ScriptsTest {

    @ParameterizedTest(name = "{0} contains kanji")
    @ValueSource(strings = {"蓋然性", "繰延税金資産", "働き甲斐", "洗い替え", "サステナビリティ経営"})
    void acceptsTermsWithKanji(String term) {
        assertThat(Scripts.containsKanji(term)).isTrue();
    }

    @ParameterizedTest(name = "{0} is kana only")
    @ValueSource(strings = {"ロボティクス", "エンゲージメント", "パンデミック",
                            "こと", "よる", "なし", "うち"})
    void rejectsKanaOnlyTerms(String term) {
        assertThat(Scripts.containsKanji(term)).isFalse();
    }

    @Test
    @DisplayName("handles empty input")
    void handlesEmpty() {
        assertThat(Scripts.containsKanji("")).isFalse();
    }
}
