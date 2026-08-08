package com.ikeda.anki;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Exercises the transport against a real server rather than a mocked client, so
 * the request envelope, batching and error handling are all covered as written.
 */
class AnkiConnectHttpTest {

    private HttpServer server;
    private final List<String> requests = new ArrayList<>();

    private AnkiConnect serving(Function<String, String> respond) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            requests.add(body);
            byte[] out = respond.apply(body).getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
        });
        server.start();
        return new AnkiConnect("http://127.0.0.1:" + server.getAddress().getPort(),
                HttpClient.newHttpClient());
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("collects headwords across the note batches it requests")
    void collectsHeadwords() throws IOException {
        AnkiConnect anki = serving(body -> body.contains("findNotes")
                ? """
                  {"result": [1, 2], "error": null}
                  """
                : """
                  {"result": [
                     {"modelName":"Basic","fields":{"Expression":{"value":"軽率"}}},
                     {"modelName":"Japanese","fields":{
                        "TargetKanji":{"value":"条約"},
                        "Expression":{"value":"2国間 で 条約 が 結ばれました"}}}
                   ], "error": null}
                  """);

        assertThat(anki.headwords()).containsExactly("軽率", "条約");
        assertThat(requests).hasSize(2);
        assertThat(requests.getFirst()).contains("\"action\":\"findNotes\"").contains("deck:*");
        assertThat(requests.get(1)).contains("\"action\":\"notesInfo\"");
    }

    @Test
    @DisplayName("sends the API version AnkiConnect expects")
    void sendsApiVersion() throws IOException {
        AnkiConnect anki = serving(body -> "{\"result\": 6, \"error\": null}");

        assertThat(anki.isAvailable()).isTrue();
        assertThat(requests.getFirst()).contains("\"version\":6");
    }

    @Test
    @DisplayName("surfaces an error reported in the response envelope")
    void surfacesReportedError() throws IOException {
        AnkiConnect anki = serving(body ->
                "{\"result\": null, \"error\": \"collection is not available\"}");

        assertThatThrownBy(anki::headwords)
                .isInstanceOf(AnkiException.class)
                .hasMessageContaining("collection is not available");
    }

    @Test
    @DisplayName("reports unavailable rather than throwing when nothing is listening")
    void reportsUnavailable() {
        var anki = new AnkiConnect("http://127.0.0.1:1", HttpClient.newHttpClient());

        assertThat(anki.isAvailable()).isFalse();
    }

    @Test
    @DisplayName("handles a collection with no notes")
    void handlesEmptyCollection() throws IOException {
        AnkiConnect anki = serving(body -> "{\"result\": [], \"error\": null}");

        assertThat(anki.headwords()).isEmpty();
        assertThat(requests).hasSize(1);   // no notesInfo call for an empty id list
    }
}
