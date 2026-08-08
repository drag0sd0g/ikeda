package com.ikeda.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ScriptsTest {

    @Test
    @DisplayName("accepts terms containing kanji")
    void acceptsKanji() {
        assertThat(Scripts.containsKanji("蓋然性")).isTrue();
        assertThat(Scripts.containsKanji("繰延税金資産")).isTrue();
    }

    @Test
    @DisplayName("accepts mixed forms, which keep their kanji")
    void acceptsMixed() {
        assertThat(Scripts.containsKanji("働き甲斐")).isTrue();
        assertThat(Scripts.containsKanji("洗い替え")).isTrue();
        assertThat(Scripts.containsKanji("サステナビリティ経営")).isTrue();
    }

    @Test
    @DisplayName("rejects katakana loanwords, which an English speaker reads for free")
    void rejectsKatakana() {
        assertThat(Scripts.containsKanji("ロボティクス")).isFalse();
        assertThat(Scripts.containsKanji("エンゲージメント")).isFalse();
        assertThat(Scripts.containsKanji("パンデミック")).isFalse();
    }

    @Test
    @DisplayName("rejects kana function words the tokeniser labels as content")
    void rejectsHiragana() {
        // Their baseline ranks are badly wrong: the reference corpus canonicalises
        // こと to 事, so こと looks among the rarest words in Japanese.
        assertThat(Scripts.containsKanji("こと")).isFalse();
        assertThat(Scripts.containsKanji("よる")).isFalse();
        assertThat(Scripts.containsKanji("なし")).isFalse();
    }

    @Test
    @DisplayName("handles empty input")
    void handlesEmpty() {
        assertThat(Scripts.containsKanji("")).isFalse();
    }
}
