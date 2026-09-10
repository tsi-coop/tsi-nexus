package org.tsicoop.nexus.framework;

import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;

public class HttpClient {

    private final java.net.http.HttpClient httpClient = java.net.http.HttpClient.newBuilder()
            .version(java.net.http.HttpClient.Version.HTTP_1_1)
            .connectTimeout(java.time.Duration.ofSeconds(60))
            .build();

    public void sendGet(String url) throws Exception {

        HttpRequest request = HttpRequest.newBuilder()
                .GET()
                .uri(URI.create(url))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

    }

    public JSONObject sendGet(String url,String authorization) throws Exception {
        JSONObject res = null;
        String resstring = null;
        JSONParser parser = new JSONParser();
        //HttpRequest request = null;
        HttpRequest request = HttpRequest.newBuilder()
                .GET()
                .uri(URI.create(url))
                .setHeader("ent_authorization", authorization)
                .setHeader("Content-Type", "application/json")
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        // print status code
        System.out.println(response.statusCode());

        // print response body
        System.out.println(response.body());
        resstring = response.body();
        res = (JSONObject) parser.parse(resstring);
        return res;
    }

    public JSONObject sendPost(String url, String authorization, JSONObject data) throws Exception {
        JSONObject res = null;
        String resstring = null;
        JSONParser parser = new JSONParser();
        HttpRequest request = HttpRequest.newBuilder()
                .POST(HttpRequest.BodyPublishers.ofString(data.toString()))
                .uri(URI.create(url))
                .setHeader("authorization", authorization)
                .setHeader("Content-Type", "application/json")
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        resstring = response.body();
        res = (JSONObject) parser.parse(resstring);
        return res;
    }

    public JSONObject sendPost(String url, JSONObject data,String authheader, String authheadervalue) throws Exception {
        JSONObject res = null;
        String resstring = null;
        JSONParser parser = new JSONParser();
        HttpRequest request = null;
        request = HttpRequest.newBuilder()
                .POST(HttpRequest.BodyPublishers.ofString(data.toString()))
                .uri(URI.create(url))
                .setHeader(authheader, authheadervalue)
                .setHeader("Content-Type", "application/json")
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        resstring = response.body();
        res = (JSONObject) parser.parse(resstring);
        return res;
    }

    public JSONObject sendPost(String url, JSONObject data, Map<String, String> headers) throws Exception {
        JSONParser parser = new JSONParser();
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .POST(HttpRequest.BodyPublishers.ofString(data.toString()))
                .uri(URI.create(url))
                .setHeader("Content-Type", "application/json");

        if (headers != null) {
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null) {
                    builder.setHeader(entry.getKey(), entry.getValue());
                }
            }
        }

        HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        return (JSONObject) parser.parse(response.body());
    }
}
