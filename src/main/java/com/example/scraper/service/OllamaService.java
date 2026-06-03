package com.example.scraper.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.Map;

@Service
public class OllamaService {

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
        this.restTemplate = new RestTemplate();
        this.objectMapper = new ObjectMapper();
    }

    /**
     * Generate text using the Ollama model via HTTP REST API.
     *
     * @param prompt the input prompt
     * @return the model's response text
     * @throws Exception if Ollama is unreachable or generation fails
     */
    public String generate(String prompt) throws Exception {
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", modelName);
        requestBody.put("prompt", prompt);
        requestBody.put("stream", false);

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
}
