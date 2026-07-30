package dev.mja00.villagerLobotomizer.utils;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import dev.mja00.villagerLobotomizer.objects.Modrinth;

import java.io.IOException;
import java.lang.reflect.Type;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Thin client for the Modrinth API used by the update checker. Encapsulates the HTTP call,
 * the 5-second timeout, and the JSON deserialisation. All call sites run on the plugin's
 * async scheduler, so the synchronous timeout is acceptable.
 */
public final class ModrinthClient {

    private static final HttpClient CLIENT = HttpClient.newHttpClient();
    // Modrinth requires a uniquely-identifying User-Agent on every request. Generic
    // client UAs (e.g. "Java/17") may be blocked as abuse. The plugin's own name
    // and version are sufficient for identification; we fall back to a stable
    // string when the version is not yet resolvable.
    private static final HttpRequest VERSION_REQUEST = HttpRequest.newBuilder()
            .GET()
            .uri(URI.create("https://api.modrinth.com/v3/project/villagerlobotomy/version"))
            .header("User-Agent", "mja00/VillagerLobotimizer")
            .build();
    private static final Gson GSON = new Gson();
    private static final long REQUEST_TIMEOUT_SECONDS = 5L;

    private ModrinthClient() {}

    /**
     * Fetches the version list for the villagerlobotomy project from the Modrinth API.
     *
     * @return the list of versions, in the order returned by the API
     * @throws IOException if the request fails, times out, or the response cannot be parsed
     */
    public static List<Modrinth.Version> fetchVersions() throws IOException {
        Type listType = new TypeToken<List<Modrinth.Version>>() {}.getType();
        try {
            return CLIENT
                    .sendAsync(VERSION_REQUEST, HttpResponse.BodyHandlers.ofString())
                    .thenApply(HttpResponse::body)
                    .thenApply(body -> GSON.<List<Modrinth.Version>>fromJson(body, listType))
                    .get(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while fetching Modrinth versions", e);
        } catch (ExecutionException e) {
            throw new IOException("Failed to fetch Modrinth versions: " + e.getMessage(), e);
        } catch (TimeoutException e) {
            throw new IOException("Timed out after " + REQUEST_TIMEOUT_SECONDS
                    + "s while fetching Modrinth versions", e);
        }
    }
}
