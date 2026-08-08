package com.ikeda.anki;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers headword extraction, which is where the collection's variety bites:
 * several note types, two different fields holding the headword, and grammar
 * decks full of things that are not words.
 */
class AnkiConnectTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static com.fasterxml.jackson.databind.JsonNode note(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    @DisplayName("reads the Expression field of a Basic note")
    void readsBasicExpression() {
        var n = note("""
                {"modelName":"Basic","fields":{
                  "Expression":{"value":"軽率"},
                  "Reading":{"value":"軽率, けいそつ"},
                  "Meaning":{"value":"rash, thoughtless"}}}
                """);

        assertThat(AnkiConnect.extractHeadwords(n)).containsExactly("軽率");
    }

    @Test
    @DisplayName("prefers TargetKanji, because iKnow notes put a sentence in Expression")
    void prefersTargetKanji() {
        var n = note("""
                {"modelName":"Japanese-2b9a7","fields":{
                  "TargetKanji":{"value":"条約"},
                  "Expression":{"value":"2国間 で 条約 が 結ばれました"},
                  "Meaning":{"value":"treaty"}}}
                """);

        assertThat(AnkiConnect.extractHeadwords(n)).containsExactly("条約");
    }

    @Test
    @DisplayName("splits comma-separated variants into separate headwords")
    void splitsVariants() {
        var n = note("""
                {"modelName":"Basic","fields":{"Expression":{"value":"妬む, 嫉む"}}}
                """);

        assertThat(AnkiConnect.extractHeadwords(n)).containsExactly("妬む", "嫉む");
    }

    @Test
    @DisplayName("strips HTML and non-breaking spaces")
    void stripsMarkup() {
        var n = note("""
                {"modelName":"Basic","fields":{"Expression":{"value":"<b>覆う</b>&nbsp;"}}}
                """);

        assertThat(AnkiConnect.extractHeadwords(n)).containsExactly("覆う");
    }

    @Test
    @DisplayName("rejects sentences and grammar patterns, which are not words")
    void rejectsNonWords() {
        var sentence = note("""
                {"modelName":"Basic","fields":{
                  "Expression":{"value":"御住所 と お名前 を 明記 して ください"}}}
                """);
        var tooLong = note("""
                {"modelName":"Basic","fields":{
                  "Expression":{"value":"AならまだしもBならまだしもCまでもが"}}}
                """);

        assertThat(AnkiConnect.extractHeadwords(sentence)).isEmpty();
        assertThat(AnkiConnect.extractHeadwords(tooLong)).isEmpty();
    }

    @Test
    @DisplayName("falls through to the next field when the preferred one is blank")
    void fallsThroughBlankFields() {
        var n = note("""
                {"modelName":"Japanese","fields":{
                  "TargetKanji":{"value":""},
                  "Expression":{"value":"几帳面"}}}
                """);

        assertThat(AnkiConnect.extractHeadwords(n)).containsExactly("几帳面");
    }

    @Test
    @DisplayName("returns nothing for a note with no usable field")
    void handlesUnusableNote() {
        var n = note("""
                {"modelName":"Cloze","fields":{"Text":{"value":"{{c1::something}}"}}}
                """);

        assertThat(AnkiConnect.extractHeadwords(n)).isEmpty();
    }
}
