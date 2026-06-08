package com.example.scraper.service;

import com.example.scraper.service.llm.LlmProvider;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

@Service
public class OllamaService implements LlmProvider {

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final String ollamaUrl;
    private final String modelName;
    private final long timeoutSeconds;

    public OllamaService(@Value("${app.ollama.url:http://localhost:11434}") String ollamaUrl,
                         @Value("${app.ollama.model:mistral}") String modelName,
                         @Value("${app.ollama.timeout-seconds:120}") long timeoutSeconds) {
        this.ollamaUrl = ollamaUrl.endsWith("/") ? ollamaUrl.substring(0, ollamaUrl.length() - 1) : ollamaUrl;
        this.modelName = modelName;
        this.timeoutSeconds = timeoutSeconds;
        // Wire the previously-dead `timeoutSeconds` field into a real factory so
        // a wedged Ollama can't hang the enrichment thread forever.
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) Duration.ofSeconds(Math.min(timeoutSeconds, 30)).toMillis());
        factory.setReadTimeout((int) Duration.ofSeconds(timeoutSeconds).toMillis());
        this.restTemplate = new RestTemplate(factory);
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public String name() { return "ollama"; }

    /**
     * Generate text using the Ollama model via HTTP REST API.
     *
     * @param prompt the input prompt
     * @return the model's response text
     * @throws Exception if Ollama is unreachable or generation fails
     */
    public String generate(String prompt) throws Exception {
        return generate(prompt, Map.of());
    }

    /**
     * Generate text with model-options (e.g. {@code format=json},
     * {@code temperature=0.2}) injected into the Ollama request body.
     */
    @Override
    public String generate(String prompt, Map<String, Object> options) throws Exception {
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", modelName);
        requestBody.put("prompt", prompt);
        requestBody.put("stream", false);
        if (options != null && !options.isEmpty()) {
            requestBody.put("options", options);
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);

        try {
            String url = ollamaUrl + "/api/generate";
            String response = restTemplate.postForObject(url, entity, String.class);

            if (response == null || response.isBlank()) {
                throw new RuntimeException("Empty response from Ollama.");
            }

            JsonNode root = objectMapper.readTree(response);
            JsonNode responseNode = root.get("response");

            if (responseNode != null && !responseNode.isNull()) {
                return responseNode.asText();
            }

            throw new RuntimeException("No response field in Ollama reply: " + response);

        } catch (RestClientException e) {
            throw new Exception("Failed to connect to Ollama at " + ollamaUrl + ": " + e.getMessage(), e);
        }
    }

    /**
     * Check if Ollama server is reachable.
     *
     * @return true if Ollama API responds
     */
    @Override
    public boolean isAvailable() {
        return isOllamaAvailable();
    }

    public boolean isOllamaAvailable() {
        try {
            String response = restTemplate.getForObject(ollamaUrl + "/api/tags", String.class);
            return response != null;
        } catch (Exception e) {
            return false;
        }
    }

    public String getModelName() {
        return modelName;
    }

    public String getOllamaUrl() {
        return ollamaUrl;
    }

    public long getTimeoutSeconds() {
        return timeoutSeconds;
    }

    /**
     * Diagnostic snapshot of the local Ollama installation: which models
     * are pulled, whether the configured model is among them, and a
     * suggested {@code ollama pull} command when it isn't. Used by
     * {@code GET /api/llm/ollama/test} so the user can see the real
     * state in the browser without running CLI commands.
     */
    public OllamaProbe probe() {
        OllamaProbe out = new OllamaProbe();
        out.ollamaUrl = ollamaUrl;
        out.configuredModel = modelName;
        out.timeoutSeconds = timeoutSeconds;
        long started = System.currentTimeMillis();
        try {
            String body = restTemplate.getForObject(ollamaUrl + "/api/tags", String.class);
            out.latencyMs = System.currentTimeMillis() - started;
            if (body == null || body.isBlank()) {
                out.reachable = false;
                out.error = "empty /api/tags response";
                return out;
            }
            out.reachable = true;
            JsonNode root = objectMapper.readTree(body);
            JsonNode models = root.path("models");
            if (models.isArray()) {
                for (JsonNode m : models) {
                    String name = m.path("name").asText("");
                    if (!name.isBlank()) {
                        // Ollama returns names like "llama3:latest" — we
                        // store them as-is and do an exact + prefix match
                        // below so the user knows whether "minimax-m3"
                        // matches "minimax-m3:latest" or similar.
                        out.availableModels.add(name);
                    }
                }
            }
            String configured = modelName;
            // Treat "<name>" as matching "<name>:<anything>" and vice-versa.
            for (String available : out.availableModels) {
                if (available.equals(configured)
                        || available.startsWith(configured + ":")
                        || configured.startsWith(available + ":")) {
                    out.modelPresent = true;
                    out.matchedAs = available;
                    break;
                }
            }
            if (!out.modelPresent) {
                out.suggestedCommand = "ollama pull " + configured;
            }
            return out;
        } catch (Exception e) {
            out.latencyMs = System.currentTimeMillis() - started;
            out.reachable = false;
            out.error = e.getClass().getSimpleName() + ": " + e.getMessage();
            return out;
        }
    }

    /** Snapshot returned by {@link #probe()}. All fields are JSON-friendly. */
    public static class OllamaProbe {
        public String ollamaUrl;
        public String configuredModel;
        public long timeoutSeconds;
        public boolean reachable;
        public long latencyMs;
        public String error;
        public java.util.List<String> availableModels = new java.util.ArrayList<>();
        public boolean modelPresent;
        public String matchedAs;
        public String suggestedCommand;
    }
}

