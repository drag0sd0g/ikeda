package com.ikeda.anki;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

public final class AnkiConnect {
    private static final Logger log = LoggerFactory.getLogger(AnkiConnect.class);

    private static final int BATCH_SIZE = 1000;

    private static final List<String> HEADWORD_FIELDS =
            List.of("TargetKanji", "Expression", "Front", "Word");

    private static final Pattern HTML_TAG = Pattern.compile("<[^>]+>");
    private static final Pattern VARIANT_SEPARATOR = Pattern.compile("[,、/／]");

    private static final int MAX_HEADWORD_LENGTH = 12;

    private static final String[][] ENTITIES = {
            {"&nbsp;", " "}, {"&lt;", "<"}, {"&gt;", ">"},
            {"&quot;", "\""}, {"&#39;", "'"}, {"&amp;", "&"},
    };


    private final AnkiGateway gateway;

    public AnkiConnect(AnkiGateway gateway) {
        this.gateway = gateway;
    }

    public static AnkiConnect withDefaults() {
        return new AnkiConnect(AnkiGateway.withDefaults());
    }

    public boolean isAvailable() {
        return gateway.isAvailable();
    }

    public List<String> headwords() {
        JsonNode ids = gateway.invoke("findNotes", gateway.params().put("query", "deck:*"));
        var headwords = new ArrayList<String>();

        for (int start = 0; start < ids.size(); start += BATCH_SIZE) {
            var batch = gateway.array();
            for (int i = start; i < Math.min(start + BATCH_SIZE, ids.size()); i++) {
                batch.add(ids.get(i).asLong());
            }
            ObjectNode params = gateway.params();
            params.set("notes", batch);

            for (JsonNode note : gateway.invoke("notesInfo", params)) {
                headwords.addAll(extractHeadwords(note));
            }
        }
        log.info("Anki: {} notes, {} headwords", ids.size(), headwords.size());
        return List.copyOf(headwords);
    }

    static List<String> extractHeadwords(JsonNode note) {
        JsonNode fields = note.path("fields");
        for (String field : HEADWORD_FIELDS) {
            String raw = fields.path(field).path("value").asText("");
            if (raw.isBlank()) {
                continue;
            }
            var found = new ArrayList<String>();
            for (String variant : VARIANT_SEPARATOR.split(clean(raw))) {
                String candidate = variant.strip();
                if (!candidate.isEmpty()
                        && !candidate.contains(" ")
                        && candidate.length() <= MAX_HEADWORD_LENGTH) {
                    found.add(candidate);
                }
            }
            return found;
        }
        return List.of();
    }

    private static String clean(String value) {
        String text = HTML_TAG.matcher(value).replaceAll("");
        for (String[] entity : ENTITIES) {
            text = text.replace(entity[0], entity[1]);
        }
        return text.replace('\u00A0', ' ').replace('\u3000', ' ').strip();
    }

}
