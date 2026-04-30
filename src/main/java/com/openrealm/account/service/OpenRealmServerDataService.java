package com.openrealm.account.service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpRequest.BodyPublisher;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openrealm.account.dto.LoginRequestDto;
import com.openrealm.account.dto.SessionTokenDto;

import lombok.AllArgsConstructor;
import lombok.Data;

@AllArgsConstructor
@Data
public class OpenRealmServerDataService implements OpenRealmDataService{
    private static final transient ObjectMapper REQUEST_MAPPER = new ObjectMapper();
    private HttpClient httpClient;
    private String baseUrl;
    
    public <T> T executeDelete(String path, Class<T> responseClass) throws Exception {
        final URI targetURI = new URI(this.baseUrl + path);
        final HttpRequest.Builder httpRequest = HttpRequest.newBuilder().header("Content-Type", "application/json")
                .uri(targetURI).DELETE();


        final HttpResponse<String> response = this.httpClient.send(httpRequest.build(),
                HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200)
            throw new IOException(response.body());

        return OpenRealmServerDataService.REQUEST_MAPPER.readValue(response.body(), responseClass);
    }

    public <T> T executePost(String path, Object object, Class<T> responseClass) throws Exception {
        final URI targetURI = new URI(this.baseUrl + path);
        final BodyPublisher body = HttpRequest.BodyPublishers
                .ofString(OpenRealmServerDataService.REQUEST_MAPPER.writeValueAsString(object));
        final HttpRequest.Builder httpRequest = HttpRequest.newBuilder().header("Content-Type", "application/json")
                .uri(targetURI).POST(body);


        HttpResponse<String> response = this.httpClient.send(httpRequest.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200)
            throw new IOException(response.body());

        return OpenRealmServerDataService.REQUEST_MAPPER.readValue(response.body(), responseClass);
    }

    public <T> T executePut(String path, Object object, Class<T> responseClass) throws Exception {
        final URI targetURI = new URI(this.baseUrl + path);
        final BodyPublisher body = HttpRequest.BodyPublishers
                .ofString(OpenRealmServerDataService.REQUEST_MAPPER.writeValueAsString(object));
        final HttpRequest.Builder httpRequest = HttpRequest.newBuilder().header("Content-Type", "application/json")
                .uri(targetURI).PUT(body);


        final HttpResponse<String> response = this.httpClient.send(httpRequest.build(),
                HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200)
            throw new IOException(response.body());

        return OpenRealmServerDataService.REQUEST_MAPPER.readValue(response.body(), responseClass);
    }

    public String executeGet(String path, Map<String, String> queryParams) throws Exception {
        URI targetURI = new URI(this.baseUrl + path);
        HttpRequest.Builder httpRequest = HttpRequest.newBuilder().header("Content-Type", "application/json")
                .uri(targetURI).GET();
        HttpResponse<String> response = this.httpClient.send(httpRequest.build(), HttpResponse.BodyHandlers.ofString());


        // TODO: Add query params
        if (response.statusCode() != 200)
            throw new IOException(response.body());

        return response.body();
    }

    public <T> T executeGet(String path, Map<String, String> queryParams, Class<T> responseClass) throws Exception {
        final URI targetURI = new URI(this.baseUrl + path);
        final HttpRequest.Builder httpRequest = HttpRequest.newBuilder().header("Content-Type", "application/json")
                .uri(targetURI).GET();

        final HttpResponse<String> response = this.httpClient.send(httpRequest.build(),
                HttpResponse.BodyHandlers.ofString());
        // TODO: Add query params
        if (response.statusCode() != 200)
            throw new IOException(response.body());

        return OpenRealmServerDataService.REQUEST_MAPPER.readValue(response.body(), responseClass);
    }

    public <T> T executeGetWithToken(String path, String token, Class<T> responseClass) throws Exception {
        final URI targetURI = new URI(this.baseUrl + path);
        final HttpRequest request = HttpRequest.newBuilder()
                .uri(targetURI)
                .GET()
                .header("Content-Type", "application/json")
                .header("Authorization", token)
                .build();
        final HttpResponse<String> response = this.httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 400) {
            throw new IOException(response.body());
        }
        return OpenRealmServerDataService.REQUEST_MAPPER.readValue(response.body(), responseClass);
    }

}
