package com.ikeda.anki;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ikeda.card.Card;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

public final class AnkiCardWriter {

    private static final Logger log = LoggerFactory.getLogger(AnkiCardWriter.class);

    private static final String TAG = "ikeda";

    private static final String CARD_CSS = """
            .card { font-family: "Hiragino Sans", sans-serif; font-size: 24px;
                    text-align: center; color: #1a1a1a; background: #ffffff; }
            .reading { font-size: 18px; color: #666; }
            .meaning { font-size: 20px; }
            .example { font-size: 18px; margin-top: 1em; text-align: left; }
            .source { font-size: 12px; color: #999; margin-top: 1em; }
            """;

    private static final String RECOGNITION_FRONT = "<div>{{Expression}}</div>";

    private static final String RECOGNITION_BACK = """
            {{FrontSide}}<hr id=answer>
            <div class="reading">{{Reading}}</div>
            <div class="meaning">{{Meaning}}</div>
            <div class="example">{{Example}}</div>
            <div class="source">{{ExampleSource}}</div>
            """;

    private static final String READING_FRONT =
            "<div>{{Expression}}</div><div class=\"meaning\">{{Meaning}}</div>";

    private static final String READING_BACK = """
            {{FrontSide}}<hr id=answer>
            <div class="reading">{{Reading}}</div>
            """;

    private final AnkiGateway gateway;

    public AnkiCardWriter(AnkiGateway gateway) {
        this.gateway = gateway;
    }

    public void ensureDeck() {
        ObjectNode params = gateway.params();
        params.put("deck", Card.DECK);
        gateway.invoke("createDeck", params);
    }

    public void ensureNoteType() {
        JsonNode existing = gateway.invoke("modelNames", null);
        for (JsonNode name : existing) {
            if (Card.NOTE_TYPE.equals(name.asText())) {
                return;
            }
        }

        ObjectNode params = gateway.params();
        params.put("modelName", Card.NOTE_TYPE);
        params.put("css", CARD_CSS);

        ArrayNode fields = gateway.array();
        new Card("", "", "", "", "", "", 0, 0).fields().keySet().forEach(fields::add);
        params.set("inOrderFields", fields);

        ArrayNode templates = gateway.array();
        templates.add(template("Recognition", RECOGNITION_FRONT, RECOGNITION_BACK));
        templates.add(template("Reading", READING_FRONT, READING_BACK));
        params.set("cardTemplates", templates);

        gateway.invoke("createModel", params);
        log.info("created note type {}", Card.NOTE_TYPE);
    }

    private ObjectNode template(String name, String front, String back) {
        ObjectNode template = gateway.params();
        template.put("Name", name);
        template.put("Front", front);
        template.put("Back", back);
        return template;
    }

    public List<Long> add(List<Card> cards) {
        if (cards.isEmpty()) {
            return List.of();
        }
        ArrayNode notes = gateway.array();
        cards.forEach(card -> notes.add(toNote(card)));

        ObjectNode params = gateway.params();
        params.set("notes", notes);

        JsonNode result = gateway.invoke("addNotes", params);
        var ids = new java.util.ArrayList<Long>();
        for (JsonNode id : result) {
            if (id.isNumber()) {
                ids.add(id.asLong());
            }
        }
        log.info("added {} of {} cards to {}", ids.size(), cards.size(), Card.DECK);
        return List.copyOf(ids);
    }

    private ObjectNode toNote(Card card) {
        ObjectNode note = gateway.params();
        note.put("deckName", Card.DECK);
        note.put("modelName", Card.NOTE_TYPE);

        ObjectNode fields = gateway.params();
        for (Map.Entry<String, String> field : card.fields().entrySet()) {
            fields.put(field.getKey(), field.getValue());
        }
        note.set("fields", fields);

        ObjectNode options = gateway.params();
        options.put("allowDuplicate", false);
        options.put("duplicateScope", "deck");
        note.set("options", options);

        ArrayNode tags = gateway.array();
        tags.add(TAG);
        note.set("tags", tags);

        return note;
    }
}
