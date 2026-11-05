package com.leash.api;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/** Tiny HTTP helper for tests that boot the app on a random port. */
public final class Http {

    public static final JsonMapper JSON = JsonMapper.builder().build();
    private final HttpClient client = HttpClient.newHttpClient();
    private final int port;

    public Http(int port) {
        this.port = port;
    }

    public record Res(int status, String body) {
        public JsonNode json() {
            return JSON.readTree(body);
        }
    }

    public Res get(String path) {
        return send(HttpRequest.newBuilder(uri(path)).GET());
    }

    public Res post(String path, String json) {
        return send(HttpRequest.newBuilder(uri(path)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json == null ? "" : json)));
    }

    public Res post(String path, String json, java.util.Map<String, String> headers) {
        var b = HttpRequest.newBuilder(uri(path)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json == null ? "" : json));
        headers.forEach(b::header);
        return send(b);
    }

    public Res put(String path, String json) {
        return send(HttpRequest.newBuilder(uri(path)).header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(json)));
    }

    public Res delete(String path) {
        return send(HttpRequest.newBuilder(uri(path)).DELETE());
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    private Res send(HttpRequest.Builder b) {
        try {
            HttpResponse<String> r = client.send(b.build(), HttpResponse.BodyHandlers.ofString());
            return new Res(r.statusCode(), r.body());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static String json(Object o) {
        return JSON.writeValueAsString(o);
    }
}
