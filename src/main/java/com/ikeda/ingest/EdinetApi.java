package com.ikeda.ingest;

import com.ikeda.support.RateLimiter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;

public final class EdinetApi {
    private static final Logger log = LoggerFactory.getLogger(EdinetApi.class);

    private static final String BASE_URL = "https://api.edinet-fsa.go.jp/api/v2";
    private static final Duration MIN_REQUEST_INTERVAL = Duration.ofSeconds(4);

    private static final int LISTING_WITH_METADATA = 2;
    private static final int DOCUMENT_AS_CSV = 5;

    private final HttpClient httpClient;
    private final RateLimiter rateLimiter;
    private final String apiKey;

    public EdinetApi(String apiKey, HttpClient httpClient, RateLimiter rateLimiter) {
        this.apiKey = apiKey;
        this.httpClient = httpClient;
        this.rateLimiter = rateLimiter;
    }

    public static EdinetApi withDefaults(String apiKey) {
        return new EdinetApi(apiKey,
                HttpClient.newHttpClient(),
                RateLimiter.minInterval(MIN_REQUEST_INTERVAL));
    }

    public byte[] listDocuments(LocalDate date) {
        return get("%s/documents.json?date=%s&type=%d&Subscription-Key=%s"
                .formatted(BASE_URL, date, LISTING_WITH_METADATA, apiKey));
    }

    public byte[] fetchCsvBundle(String docId) {
        return get("%s/documents/%s?type=%d&Subscription-Key=%s"
                .formatted(BASE_URL, docId, DOCUMENT_AS_CSV, apiKey));
    }

    private byte[] get(String url) {
        try {
            rateLimiter.acquire();
            var request = HttpRequest.newBuilder(URI.create(url)).GET().build();
            HttpResponse<byte[]> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());

            if (response.statusCode() != 200) {
                throw new EdinetException(
                        "EDINET returned HTTP %d".formatted(response.statusCode()));
            }
            log.debug("fetched {} bytes", response.body().length);
            return response.body();

        } catch (IOException e) {
            throw new EdinetException("EDINET request failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new EdinetException("interrupted during EDINET request", e);
        }
    }
}
