package com.ikeda.gloss;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class JmdictGlossSource implements GlossSource {

    private static final Logger log = LoggerFactory.getLogger(JmdictGlossSource.class);

    private static final String ENGLISH = "eng";
    private static final int MAX_MEANINGS = 6;

    private final Map<String, Gloss> byHeadword;

    private JmdictGlossSource(Map<String, Gloss> byHeadword) {
        this.byHeadword = byHeadword;
    }

    public static JmdictGlossSource load(Path json) {
        var index = new HashMap<String, Gloss>();
        var factory = new JsonFactory();
        var mapper = new ObjectMapper();

        try (JsonParser parser = factory.createParser(json.toFile())) {
            while (parser.nextToken() != null) {
                if (parser.currentToken() == JsonToken.FIELD_NAME
                        && "words".equals(parser.currentName())) {
                    parser.nextToken();
                    readEntries(parser, mapper, index);
                    break;
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read dictionary " + json, e);
        }
        log.info("dictionary loaded: {} headwords from {}", index.size(), json.getFileName());
        return new JmdictGlossSource(index);
    }

    private static void readEntries(JsonParser parser, ObjectMapper mapper,
                                    Map<String, Gloss> index) throws IOException {
        while (parser.nextToken() == JsonToken.START_OBJECT) {
            JsonNode entry = mapper.readTree(parser);
            List<String> meanings = englishMeanings(entry);
            if (meanings.isEmpty()) {
                continue;
            }
            String reading = firstText(entry.path("kana"));
            for (String headword : headwords(entry)) {
                index.putIfAbsent(headword, new Gloss(headword, reading, meanings));
            }
        }
    }

    private static List<String> headwords(JsonNode entry) {
        var written = new ArrayList<String>();
        for (JsonNode kanji : entry.path("kanji")) {
            written.add(kanji.path("text").asText());
        }
        if (written.isEmpty()) {
            for (JsonNode kana : entry.path("kana")) {
                written.add(kana.path("text").asText());
            }
        }
        return written;
    }

    private static List<String> englishMeanings(JsonNode entry) {
        var meanings = new ArrayList<String>();
        for (JsonNode sense : entry.path("sense")) {
            for (JsonNode gloss : sense.path("gloss")) {
                if (ENGLISH.equals(gloss.path("lang").asText()) && meanings.size() < MAX_MEANINGS) {
                    meanings.add(gloss.path("text").asText());
                }
            }
        }
        return List.copyOf(meanings);
    }

    private static String firstText(JsonNode array) {
        return array.isEmpty() ? "" : array.get(0).path("text").asText();
    }

    @Override
    public Optional<Gloss> lookup(String headword) {
        return Optional.ofNullable(byHeadword.get(headword));
    }

    public int size() {
        return byHeadword.size();
    }
}
