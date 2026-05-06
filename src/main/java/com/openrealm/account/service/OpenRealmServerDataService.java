package com.openrealm.account.service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpRequest.BodyPublisher;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openrealm.account.dto.LoginRequestDto;
import com.openrealm.account.dto.SessionTokenDto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

@AllArgsConstructor
@Data
@Slf4j
public class OpenRealmServerDataService implements OpenRealmDataService{
    private static final transient ObjectMapper REQUEST_MAPPER = new ObjectMapper();
    private HttpClient httpClient;
    private String baseUrl;

    /**
     * Log elapsed time for a single REST round-trip from the game server to
     * the data service. Format matches the request filter on the data
     * service side so log lines are filterable with the same grep across
     * services. Slow calls (&gt;250 ms) escalate to WARN.
     */
    private static void logTiming(String method, String path, int status, long startNanos) {
        final long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L;
        if (elapsedMs >= 250) {
            log.warn("[DATA-CALL] {} {} -> {} in {} ms (slow)", method, path, status, elapsedMs);
        } else {
            log.info("[DATA-CALL] {} {} -> {} in {} ms", method, path, status, elapsedMs);
        }
    }

    public <T> T executeDelete(String path, Class<T> responseClass) throws Exception {
        final long t0 = System.nanoTime();
        final URI targetURI = new URI(this.baseUrl + path);
        final HttpRequest.Builder httpRequest = HttpRequest.newBuilder().header("Content-Type", "application/json")
                .uri(targetURI).DELETE();


        final HttpResponse<String> response = this.httpClient.send(httpRequest.build(),
                HttpResponse.BodyHandlers.ofString());
        logTiming("DELETE", path, response.statusCode(), t0);
        if (response.statusCode() != 200)
            throw new IOException(response.body());

        return OpenRealmServerDataService.REQUEST_MAPPER.readValue(response.body(), responseClass);
    }

    public <T> T executePost(String path, Object object, Class<T> responseClass) throws Exception {
        final long t0 = System.nanoTime();
        final URI targetURI = new URI(this.baseUrl + path);
        final BodyPublisher body = HttpRequest.BodyPublishers
                .ofString(OpenRealmServerDataService.REQUEST_MAPPER.writeValueAsString(object));
        final HttpRequest.Builder httpRequest = HttpRequest.newBuilder().header("Content-Type", "application/json")
                .uri(targetURI).POST(body);


        HttpResponse<String> response = this.httpClient.send(httpRequest.build(), HttpResponse.BodyHandlers.ofString());
        logTiming("POST", path, response.statusCode(), t0);
        if (response.statusCode() != 200)
            throw new IOException(response.body());

        return OpenRealmServerDataService.REQUEST_MAPPER.readValue(response.body(), responseClass);
    }

    public <T> T executePut(String path, Object object, Class<T> responseClass) throws Exception {
        final long t0 = System.nanoTime();
        final URI targetURI = new URI(this.baseUrl + path);
        final BodyPublisher body = HttpRequest.BodyPublishers
                .ofString(OpenRealmServerDataService.REQUEST_MAPPER.writeValueAsString(object));
        final HttpRequest.Builder httpRequest = HttpRequest.newBuilder().header("Content-Type", "application/json")
                .uri(targetURI).PUT(body);


        final HttpResponse<String> response = this.httpClient.send(httpRequest.build(),
                HttpResponse.BodyHandlers.ofString());
        logTiming("PUT", path, response.statusCode(), t0);
        if (response.statusCode() != 200)
            throw new IOException(response.body());

        return OpenRealmServerDataService.REQUEST_MAPPER.readValue(response.body(), responseClass);
    }

    public String executeGet(String path, Map<String, String> queryParams) throws Exception {
        final long t0 = System.nanoTime();
        URI targetURI = new URI(this.baseUrl + path);
        HttpRequest.Builder httpRequest = HttpRequest.newBuilder().header("Content-Type", "application/json")
                .uri(targetURI).GET();
        HttpResponse<String> response = this.httpClient.send(httpRequest.build(), HttpResponse.BodyHandlers.ofString());


        logTiming("GET", path, response.statusCode(), t0);
        // TODO: Add query params
        if (response.statusCode() != 200)
            throw new IOException(response.body());

        return response.body();
    }

    public <T> T executeGet(String path, Map<String, String> queryParams, Class<T> responseClass) throws Exception {
        final long t0 = System.nanoTime();
        final URI targetURI = new URI(this.baseUrl + path);
        final HttpRequest.Builder httpRequest = HttpRequest.newBuilder().header("Content-Type", "application/json")
                .uri(targetURI).GET();

        final HttpResponse<String> response = this.httpClient.send(httpRequest.build(),
                HttpResponse.BodyHandlers.ofString());
        logTiming("GET", path, response.statusCode(), t0);
        // TODO: Add query params
        if (response.statusCode() != 200)
            throw new IOException(response.body());

        return OpenRealmServerDataService.REQUEST_MAPPER.readValue(response.body(), responseClass);
    }

    /**
     * Async POST. Returns a CompletableFuture so the caller can fire-and-log
     * (chest saves, fame updates, character persistence) without parking a
     * thread for the HTTP round-trip. The response body is parsed on the
     * JDK HttpClient's selector; the returned future completes (or excepts)
     * on whatever executor consumes it.
     *
     * <p>Use over {@link #executePost(String, Object, Class)} on hot
     * persistence paths (player disconnect, realm shutdown, vault exit) —
     * those used to park a {@code WorkerThread} pool slot for the full
     * round-trip and could starve the tick fan-out under burst.
     */
    public <T> CompletableFuture<T> executePostAsync(String path, Object object, Class<T> responseClass) {
        final long t0 = System.nanoTime();
        try {
            final URI targetURI = new URI(this.baseUrl + path);
            final BodyPublisher body = HttpRequest.BodyPublishers
                    .ofString(OpenRealmServerDataService.REQUEST_MAPPER.writeValueAsString(object));
            final HttpRequest httpRequest = HttpRequest.newBuilder()
                    .header("Content-Type", "application/json")
                    .uri(targetURI).POST(body).build();
            return this.httpClient.sendAsync(httpRequest, HttpResponse.BodyHandlers.ofString())
                    .thenApply(response -> {
                        logTiming("POST", path, response.statusCode(), t0);
                        if (response.statusCode() != 200) {
                            throw new CompletionException(new IOException(response.body()));
                        }
                        try {
                            return OpenRealmServerDataService.REQUEST_MAPPER.readValue(response.body(), responseClass);
                        } catch (Exception e) {
                            throw new CompletionException(e);
                        }
                    });
        } catch (Exception e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    /** Async GET — same rationale as executePostAsync but for fetches. */
    public <T> CompletableFuture<T> executeGetAsync(String path, Class<T> responseClass) {
        final long t0 = System.nanoTime();
        try {
            final URI targetURI = new URI(this.baseUrl + path);
            final HttpRequest httpRequest = HttpRequest.newBuilder()
                    .header("Content-Type", "application/json")
                    .uri(targetURI).GET().build();
            return this.httpClient.sendAsync(httpRequest, HttpResponse.BodyHandlers.ofString())
                    .thenApply(response -> {
                        logTiming("GET", path, response.statusCode(), t0);
                        if (response.statusCode() != 200) {
                            throw new CompletionException(new IOException(response.body()));
                        }
                        try {
                            return OpenRealmServerDataService.REQUEST_MAPPER.readValue(response.body(), responseClass);
                        } catch (Exception e) {
                            throw new CompletionException(e);
                        }
                    });
        } catch (Exception e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    public <T> T executeGetWithToken(String path, String token, Class<T> responseClass) throws Exception {
        final long t0 = System.nanoTime();
        final URI targetURI = new URI(this.baseUrl + path);
        final HttpRequest request = HttpRequest.newBuilder()
                .uri(targetURI)
                .GET()
                .header("Content-Type", "application/json")
                .header("Authorization", token)
                .build();
        final HttpResponse<String> response = this.httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        logTiming("GET", path, response.statusCode(), t0);
        if (response.statusCode() >= 400) {
            throw new IOException(response.body());
        }
        return OpenRealmServerDataService.REQUEST_MAPPER.readValue(response.body(), responseClass);
    }

}
