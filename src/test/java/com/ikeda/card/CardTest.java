package com.ikeda.card;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CardTest {

    private static Card card() {
        return new Card("往査", "オウサ", "site visit", "社外監査役は往査を実施しております。",
                "福井鉄道株式会社 2026-06-26", "S100YLJZ", 127_000, 56);
    }

    @Test
    @DisplayName("exposes every field the note type declares")
    void exposesAllFields() {
        assertThat(card().fields()).containsOnlyKeys(
                Card.FIELD_EXPRESSION, Card.FIELD_READING, Card.FIELD_MEANING,
                Card.FIELD_EXAMPLE, Card.FIELD_SOURCE, Card.FIELD_DOC_ID,
                Card.FIELD_RANK, Card.FIELD_DOCUMENT_FREQUENCY);
    }

    @Test
    @DisplayName("puts the expression first, so it leads the note editor")
    void expressionComesFirst() {
        assertThat(card().fields().keySet().iterator().next()).isEqualTo(Card.FIELD_EXPRESSION);
    }

    @Test
    @DisplayName("leaves the rank blank when the word is absent from the baseline")
    void blankRankWhenUnscored() {
        var unscored = new Card("末残", "マツザン", "", "例文です。", "会社", "S1", 0, 59);

        assertThat(unscored.fields()).containsEntry(Card.FIELD_RANK, "");
    }

    @Test
    @DisplayName("targets its own deck and note type")
    void targetsOwnDeckAndNoteType() {
        assertThat(Card.DECK).isEqualTo("金融::有報");
        assertThat(Card.NOTE_TYPE).isEqualTo("Ikeda Financial Japanese");
    }
}
