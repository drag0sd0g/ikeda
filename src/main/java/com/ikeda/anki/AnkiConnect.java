package com.ikeda.anki;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Reads the local Anki collection through the AnkiConnect add-on.
 *
 * <p>Read only. The Ikeda deck is built separately and nothing here writes to
 * the collection.
 */
public final class AnkiConnect {

    private static final Logger log = LoggerFactory.getLogger(AnkiConnect.class);

    private static final String DEFAULT_ENDPOINT = "http://127.0.0.1:8765";
    private static final int API_VERSION = 6;
    private static final int BATCH_SIZE = 1000;

    /**
     * Fields that hold a headword, in preference order.
     *
     * <p>Order matters: the iKnow-derived note types keep the headword in
     * {@code TargetKanji} and a whole example sentence in {@code Expression}, so
     * checking {@code Expression} first would harvest sentences.
     */
    private static final List<String> HEADWORD_FIELDS =
            List.of("TargetKanji", "Expression", "Front", "Word");

    private static final Pattern HTML_TAG = Pattern.compile("<[^>]+>");
    private static final Pattern VARIANT_SEPARATOR = Pattern.compile("[,、/／]");

    /** Longer than this is a sentence or a grammar pattern, not a word. */
    private static final int MAX_HEADWORD_LENGTH = 12;

    private static final String[][] ENTITIES = {
            {"&nbsp;", " "}, {"&lt;", "<"}, {"&gt;", ">"},
            {"&quot;", "\""}, {"&#39;", "'"}, {"&amp;", "&"},
    };

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HttpClient httpClient;
    private final String endpoint;

    public AnkiConnect(String endpoint, HttpClient httpClient) {
        this.endpoint = endpoint;
        this.httpClient = httpClient;
    }

    public static AnkiConnect withDefaults() {
        return new AnkiConnect(DEFAULT_ENDPOINT, HttpClient.newHttpClient());
    }

    /** True when AnkiConnect answers, so callers can degrade rather than fail. */
    public boolean isAvailable() {
        try {
            invoke("version", null);
            return true;
        } catch (AnkiException e) {
            log.debug("AnkiConnect unavailable: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Every headword in the collection.
     *
     * <p>All of it counts as known, on the collection owner's instruction: the
     * review metadata spans several backups and accounts over many years, so card
     * maturity says nothing reliable about whether a word was learned.
     */
    public List<String> headwords() {
        JsonNode ids = invoke("findNotes", MAPPER.createObjectNode().put("query", "deck:*"));
        var headwords = new ArrayList<String>();

        for (int start = 0; start < ids.size(); start += BATCH_SIZE) {
            var batch = MAPPER.createArrayNode();
            for (int i = start; i < Math.min(start + BATCH_SIZE, ids.size()); i++) {
                batch.add(ids.get(i).asLong());
            }
            ObjectNode params = MAPPER.createObjectNode();
            params.set("notes", batch);

            for (JsonNode note : invoke("notesInfo", params)) {
                headwords.addAll(extractHeadwords(note));
            }
        }
        log.info("Anki: {} notes, {} headwords", ids.size(), headwords.size());
        return List.copyOf(headwords);
    }

    /** Package-private for testing: pulls usable headwords out of one note's fields. */
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

    /**
     * Strips markup from a field value.
     *
     * <p>Anki stores fields as HTML, so an entity can survive either decoded or
     * literal depending on how the note was authored or imported. Both forms are
     * handled: a stray {@code &nbsp;} welded to a headword would otherwise stop
     * it matching any corpus term.
     */
    private static String clean(String value) {
        String text = HTML_TAG.matcher(value).replaceAll("");
        for (String[] entity : ENTITIES) {
            text = text.replace(entity[0], entity[1]);
        }
        return text.replace('\u00A0', ' ').replace('\u3000', ' ').strip();
    }

    private JsonNode invoke(String action, JsonNode params) {
        ObjectNode body = MAPPER.createObjectNode()
                .put("action", action)
                .put("version", API_VERSION);
        if (params != null) {
            body.set("params", params);
        }
        try {
            HttpResponse<String> response = httpClient.send(
                    HttpRequest.newBuilder(URI.create(endpoint))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(
                                    body.toString(), StandardCharsets.UTF_8))
                            .build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

            JsonNode parsed = MAPPER.readTree(response.body());
            if (!parsed.path("error").isNull() && parsed.hasNonNull("error")) {
                throw new AnkiException("AnkiConnect error: " + parsed.get("error").asText());
            }
            return parsed.path("result");

        } catch (IOException e) {
            throw new AnkiException("cannot reach AnkiConnect at " + endpoint, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AnkiException("interrupted talking to AnkiConnect", e);
        }
    }
}
