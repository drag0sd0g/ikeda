package com.ikeda.anki;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

public final class AnkiGateway {

    public static final String DEFAULT_ENDPOINT = "http://127.0.0.1:8765";

    private static final int API_VERSION = 6;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HttpClient httpClient;
    private final String endpoint;

    public AnkiGateway(String endpoint, HttpClient httpClient) {
        this.endpoint = endpoint;
        this.httpClient = httpClient;
    }

    public static AnkiGateway withDefaults() {
        return new AnkiGateway(DEFAULT_ENDPOINT, HttpClient.newHttpClient());
    }

    public ObjectNode params() {
        return MAPPER.createObjectNode();
    }

    public com.fasterxml.jackson.databind.node.ArrayNode array() {
        return MAPPER.createArrayNode();
    }

    public JsonNode invoke(String action, JsonNode params) {
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
            if (parsed.hasNonNull("error")) {
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

    public boolean isAvailable() {
        try {
            invoke("version", null);
            return true;
        } catch (AnkiException e) {
            return false;
        }
    }
}
