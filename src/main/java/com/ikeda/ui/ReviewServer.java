package com.ikeda.ui;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ikeda.review.CandidateStatus;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;

public final class ReviewServer implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ReviewServer.class);

    private static final String PAGE_RESOURCE = "/review.html";
    private static final int BATCH_SIZE = 40;
    private static final int NO_BACKLOG = 0;
    private static final int STOP_IMMEDIATELY = 0;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ReviewQueue queue;
    private final HttpServer server;

    public ReviewServer(ReviewQueue queue, int port) {
        this.queue = queue;
        try {
            this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), NO_BACKLOG);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot bind review server to port " + port, e);
        }
        route("/", this::servePage);
        route("/api/queue", this::serveQueue);
        route("/api/verdict", this::recordVerdict);
        route("/api/undo", this::undoVerdict);
        route("/api/progress", this::serveProgress);
    }

    private void route(String path, Handler handler) {
        server.createContext(path, exchange -> {
            try {
                handler.handle(exchange);
            } catch (RuntimeException e) {
                log.error("request to {} failed", exchange.getRequestURI(), e);
                respond(exchange, 500, "text/plain", "internal error: " + e);
            }
        });
    }

    @FunctionalInterface
    private interface Handler {
        void handle(HttpExchange exchange) throws IOException;
    }

    public int port() {
        return server.getAddress().getPort();
    }

    public String address() {
        return "http://127.0.0.1:" + port();
    }

    public void start() {
        server.start();
        log.info("review UI at {}", address());
    }

    @Override
    public void close() {
        server.stop(STOP_IMMEDIATELY);
    }

    private void servePage(HttpExchange exchange) throws IOException {
        try (InputStream page = ReviewServer.class.getResourceAsStream(PAGE_RESOURCE)) {
            if (page == null) {
                respond(exchange, 500, "text/plain", "missing " + PAGE_RESOURCE);
                return;
            }
            respond(exchange, 200, "text/html", new String(page.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    private void serveQueue(HttpExchange exchange) throws IOException {
        respondJson(exchange, MAPPER.writeValueAsString(queue.next(BATCH_SIZE)));
    }

    private void serveProgress(HttpExchange exchange) throws IOException {
        Map<CandidateStatus, Long> counts = queue.progress();
        var payload = MAPPER.createObjectNode();
        counts.forEach((status, count) -> payload.put(status.name(), count));
        respondJson(exchange, payload.toString());
    }

    private void recordVerdict(HttpExchange exchange) throws IOException {
        JsonNode body = readBody(exchange);
        String term = body.path("term").asText();
        var verdict = CandidateStatus.parse(body.path("verdict").asText());

        if (term.isBlank() || verdict.isEmpty()) {
            respond(exchange, 400, "text/plain", "term and verdict are required");
            return;
        }
        queue.record(term, verdict.get());
        respondJson(exchange, "{\"ok\":true}");
    }

    private void undoVerdict(HttpExchange exchange) throws IOException {
        String term = readBody(exchange).path("term").asText();
        if (term.isBlank()) {
            respond(exchange, 400, "text/plain", "term is required");
            return;
        }
        queue.undo(term);
        respondJson(exchange, "{\"ok\":true}");
    }

    private static JsonNode readBody(HttpExchange exchange) throws IOException {
        return MAPPER.readTree(new String(
                exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
    }

    private static void respondJson(HttpExchange exchange, String json) throws IOException {
        respond(exchange, 200, "application/json", json);
    }

    private static void respond(HttpExchange exchange, int status, String type, String body)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", type + "; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
