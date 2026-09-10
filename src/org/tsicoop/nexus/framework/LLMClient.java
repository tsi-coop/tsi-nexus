package org.tsicoop.nexus.framework;

import org.json.simple.JSONArray;
import org.json.simple.JSONObject;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Provider-neutral text generation client for Nexus' internal LLM calls.
 *
 * Supported providers:
 *   openai-compatible  vLLM or any /v1/chat/completions compatible endpoint
 *   openai             OpenAI chat completions
 *   sarvam             Sarvam chat-completions compatible endpoint
 *   claude             Anthropic Messages API
 *   gemini             Gemini generateContent API
 *
 * Backward compatibility:
 *   VLLM_URL and VLLM_MODEL are still accepted when LLM_* is not configured.
 */
public class LLMClient {

    private static final String DEFAULT_LOCAL_URL = "http://192.168.1.77:8001";
    private static final String DEFAULT_LOCAL_MODEL = "gemma-4-26B-A4B-it";

    public static boolean isConfigured() {
        return !model().isEmpty() && !baseUrl().isEmpty();
    }

    public static String provider() {
        String provider = env("LLM_PROVIDER");
        if (!provider.isEmpty()) return provider.toLowerCase();
        return "openai-compatible";
    }

    public static String model() {
        String model = env("LLM_MODEL");
        if (!model.isEmpty()) return model;
        model = env("VLLM_MODEL");
        return model.isEmpty() ? DEFAULT_LOCAL_MODEL : model;
    }

    public static String baseUrl() {
        String url = env("LLM_BASE_URL");
        if (!url.isEmpty()) return trimSlash(url);
        url = env("VLLM_URL");
        return trimSlash(url.isEmpty() ? DEFAULT_LOCAL_URL : url);
    }

    public static String apiKeyConfigured() {
        return env("LLM_API_KEY").isEmpty() ? "false" : "true";
    }

    @SuppressWarnings("unchecked")
    public static JSONObject chat(JSONArray messages, int maxTokens, double temperature) throws Exception {
        JSONObject request = new JSONObject();
        request.put("messages", messages);
        request.put("max_tokens", maxTokens);
        request.put("temperature", temperature);
        return chat(request);
    }

    @SuppressWarnings("unchecked")
    public static JSONObject chat(JSONObject request) throws Exception {
        String provider = provider();
        if ("claude".equals(provider) || "anthropic".equals(provider)) {
            return callClaude(request);
        }
        if ("gemini".equals(provider) || "google".equals(provider)) {
            return callGemini(request);
        }
        return callChatCompletions(request, provider);
    }

    @SuppressWarnings("unchecked")
    public static JSONObject ping() {
        try {
            JSONArray messages = new JSONArray();
            JSONObject user = new JSONObject();
            user.put("role", "user");
            user.put("content", "Reply with OK.");
            messages.add(user);
            JSONObject response = chat(messages, 16, 0.0);
            String content = extractContent(response);

            JSONObject out = new JSONObject();
            out.put("success", content != null && !content.isBlank());
            out.put("provider", provider());
            out.put("base_url", baseUrl());
            out.put("model", model());
            out.put("api_key_configured", apiKeyConfigured());
            return out;
        } catch (Exception e) {
            JSONObject out = new JSONObject();
            out.put("success", false);
            out.put("provider", provider());
            out.put("base_url", baseUrl());
            out.put("model", model());
            out.put("api_key_configured", apiKeyConfigured());
            out.put("error", e.getMessage());
            return out;
        }
    }

    public static String extractContent(JSONObject llmResponse) {
        if (llmResponse == null) return null;
        Object content = llmResponse.get("content");
        if (content instanceof String) return (String) content;
        try {
            JSONArray choices = (JSONArray) llmResponse.get("choices");
            if (choices != null && !choices.isEmpty()) {
                JSONObject msg = (JSONObject) ((JSONObject) choices.get(0)).get("message");
                if (msg != null) return (String) msg.get("content");
            }
        } catch (Exception ignore) {}
        return null;
    }

    public static String extractReasoningContent(JSONObject llmResponse) {
        if (llmResponse == null) return null;
        Object reasoning = llmResponse.get("reasoning_content");
        if (reasoning instanceof String) return (String) reasoning;
        try {
            JSONArray choices = (JSONArray) llmResponse.get("choices");
            if (choices != null && !choices.isEmpty()) {
                JSONObject msg = (JSONObject) ((JSONObject) choices.get(0)).get("message");
                if (msg != null) return (String) msg.get("reasoning_content");
            }
        } catch (Exception ignore) {}
        return null;
    }

    @SuppressWarnings("unchecked")
    private static JSONObject callChatCompletions(JSONObject request, String provider) throws Exception {
        JSONObject body = copyGenerationFields(request, "max_tokens");
        body.put("model", model());
        body.put("messages", request.get("messages"));

        Map<String, String> headers = new LinkedHashMap<>();
        String apiKey = env("LLM_API_KEY");
        if ("sarvam".equals(provider)) {
            if (!apiKey.isEmpty()) headers.put(envOrDefault("LLM_AUTH_HEADER", "api-subscription-key"), apiKey);
        } else {
            headers.put("Authorization", "Bearer " + (apiKey.isEmpty() ? "dummy" : apiKey));
        }

        String path = envOrDefault("LLM_CHAT_PATH", "/v1/chat/completions");
        return new HttpClient().sendPost(baseUrl() + path, body, headers);
    }

    @SuppressWarnings("unchecked")
    private static JSONObject callClaude(JSONObject request) throws Exception {
        JSONArray source = (JSONArray) request.get("messages");
        JSONArray messages = new JSONArray();
        StringBuilder system = new StringBuilder();

        for (Object item : source) {
            JSONObject msg = (JSONObject) item;
            String role = String.valueOf(msg.get("role"));
            String content = String.valueOf(msg.get("content"));
            if ("system".equals(role)) {
                if (system.length() > 0) system.append("\n\n");
                system.append(content);
            } else {
                JSONObject mapped = new JSONObject();
                mapped.put("role", "assistant".equals(role) ? "assistant" : "user");
                mapped.put("content", content);
                messages.add(mapped);
            }
        }

        JSONObject body = copyGenerationFields(request, "max_tokens");
        body.put("model", model());
        body.put("messages", messages);
        if (system.length() > 0) body.put("system", system.toString());

        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("x-api-key", env("LLM_API_KEY"));
        headers.put("anthropic-version", envOrDefault("LLM_API_VERSION", "2023-06-01"));

        JSONObject raw = new HttpClient().sendPost(baseUrl() + envOrDefault("LLM_CHAT_PATH", "/v1/messages"), body, headers);
        return normalizeClaude(raw);
    }

    @SuppressWarnings("unchecked")
    private static JSONObject callGemini(JSONObject request) throws Exception {
        JSONArray source = (JSONArray) request.get("messages");
        JSONArray contents = new JSONArray();
        StringBuilder system = new StringBuilder();

        for (Object item : source) {
            JSONObject msg = (JSONObject) item;
            String role = String.valueOf(msg.get("role"));
            String content = String.valueOf(msg.get("content"));
            if ("system".equals(role)) {
                if (system.length() > 0) system.append("\n\n");
                system.append(content);
                continue;
            }

            JSONObject part = new JSONObject();
            part.put("text", content);
            JSONArray parts = new JSONArray();
            parts.add(part);

            JSONObject mapped = new JSONObject();
            mapped.put("role", "assistant".equals(role) ? "model" : "user");
            mapped.put("parts", parts);
            contents.add(mapped);
        }

        JSONObject body = new JSONObject();
        body.put("contents", contents);
        if (system.length() > 0) {
            JSONObject sysPart = new JSONObject();
            sysPart.put("text", system.toString());
            JSONArray sysParts = new JSONArray();
            sysParts.add(sysPart);
            JSONObject sys = new JSONObject();
            sys.put("parts", sysParts);
            body.put("systemInstruction", sys);
        }

        JSONObject generationConfig = new JSONObject();
        if (request.get("max_tokens") != null) generationConfig.put("maxOutputTokens", request.get("max_tokens"));
        if (request.get("temperature") != null) generationConfig.put("temperature", request.get("temperature"));
        if (!generationConfig.isEmpty()) body.put("generationConfig", generationConfig);

        String encodedModel = URLEncoder.encode(model(), StandardCharsets.UTF_8).replace("+", "%20");
        String path = envOrDefault("LLM_CHAT_PATH", "/v1beta/models/" + encodedModel + ":generateContent");
        String url = baseUrl() + path;
        String apiKey = env("LLM_API_KEY");
        Map<String, String> headers = new LinkedHashMap<>();
        if (!apiKey.isEmpty()) headers.put(envOrDefault("LLM_AUTH_HEADER", "x-goog-api-key"), apiKey);

        JSONObject raw = new HttpClient().sendPost(url, body, headers);
        return normalizeGemini(raw);
    }

    @SuppressWarnings("unchecked")
    private static JSONObject copyGenerationFields(JSONObject request, String tokenFieldName) {
        JSONObject body = new JSONObject();
        if (request.get("max_tokens") != null) body.put(tokenFieldName, request.get("max_tokens"));
        if (request.get("temperature") != null) body.put("temperature", request.get("temperature"));
        if (request.get("frequency_penalty") != null) body.put("frequency_penalty", request.get("frequency_penalty"));
        return body;
    }

    @SuppressWarnings("unchecked")
    private static JSONObject normalizeClaude(JSONObject raw) {
        JSONObject out = new JSONObject();
        out.put("raw", raw);
        StringBuilder text = new StringBuilder();
        Object content = raw.get("content");
        if (content instanceof JSONArray) {
            for (Object item : (JSONArray) content) {
                if (item instanceof JSONObject) {
                    Object part = ((JSONObject) item).get("text");
                    if (part != null) text.append(part);
                }
            }
        }
        out.put("content", text.toString());
        return out;
    }

    @SuppressWarnings("unchecked")
    private static JSONObject normalizeGemini(JSONObject raw) {
        JSONObject out = new JSONObject();
        out.put("raw", raw);
        StringBuilder text = new StringBuilder();
        Object candidates = raw.get("candidates");
        if (candidates instanceof JSONArray && !((JSONArray) candidates).isEmpty()) {
            JSONObject first = (JSONObject) ((JSONArray) candidates).get(0);
            JSONObject content = (JSONObject) first.get("content");
            if (content != null && content.get("parts") instanceof JSONArray) {
                for (Object item : (JSONArray) content.get("parts")) {
                    if (item instanceof JSONObject && ((JSONObject) item).get("text") != null) {
                        text.append(((JSONObject) item).get("text"));
                    }
                }
            }
        }
        out.put("content", text.toString());
        return out;
    }

    private static String env(String key) {
        String value = System.getenv(key);
        return value == null ? "" : value.trim();
    }

    private static String envOrDefault(String key, String fallback) {
        String value = env(key);
        return value.isEmpty() ? fallback : value;
    }

    private static String trimSlash(String value) {
        return value == null ? "" : value.replaceAll("/$", "");
    }
}
