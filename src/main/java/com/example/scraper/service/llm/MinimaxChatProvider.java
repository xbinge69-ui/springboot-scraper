package com.example.scraper.service.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.Map;

/**
 * LLM provider backed by <a href="https://www.minimaxi.com">MiniMax</a>'s
 * hosted chat-completions API. Activated when the user ticks the
 * "Use MiniMax" checkbox on the pipeline page.
 *
 * <p>Wire-compatible with the OpenAI {@code /v1/chat/completions}
 * schema: we send {@code Authorization: Bearer <key>}, the
 * {@code model} chosen by config, and the system/user split.
 *
 * <h3>Credential handling</h3>
 * The API key is read <strong>only</strong> from the
 * {@code MINIMAX_API_KEY} environment variable (or
 * {@code app.minimax.api-key} if you really must, for local dev).
 * It is never logged, never returned in error messages, and never
 * persisted. Use a placeholder / env var in source.
 */
@Service
public class MinimaxChatProvider implements LlmProvider {

    private static final Logger log = LoggerFactory.getLogger(MinimaxChatProvider.class);

    private final RestTemplate restTemplate;
    private final ObjectMapper mapper = new ObjectMapper();
    private final String apiKey;
    private final String baseUrl;
    private final String model;
    private final long timeoutSeconds;
    private final boolean enabled;

    public MinimaxChatProvider(
            @Value("${MINIMAX_API_KEY:}") String envKey,
            @Value("${app.minimax.api-key:}") String propsKey,
            @Value("${app.minimax.base-url:https://api.minimaxi.com}") String baseUrl,
            @Value("${app.minimax.model:MiniMax-Text-01}") String model,
            @Value("${app.minimax.timeout-seconds:120}") long timeoutSeconds,
            @Value("${app.minimax.enabled:false}") boolean enabled) {
        // Prefer the env var. Fall back to property. Either blank → disabled.
        String key = (envKey != null && !envKey.isBlank()) ? envKey : propsKey;
        this.apiKey = (key == null) ? "" : key.trim();
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.model = model;
        this.timeoutSeconds = timeoutSeconds;
        this.enabled = enabled && !this.apiKey.isBlank();

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) Duration.ofSeconds(Math.min(timeoutSeconds, 30)).toMillis());
        factory.setReadTimeout((int) Duration.ofSeconds(timeoutSeconds).toMillis());
        this.restTemplate = new RestTemplate(factory);

        if (this.enabled) {
            log.info("MiniMax provider enabled: model={}, baseUrl={}", model, baseUrl);
        } else {
            log.info("MiniMax provider disabled (no MINIMAX_API_KEY set or app.minimax.enabled=false)");
        }
    }

    @Override
    public String name() { return "minimax-chat"; }

    @Override
    public boolean isAvailable() {
        return enabled;
    }

    @Override
    public String generate(String prompt, Map<String, Object> options) throws Exception {
        if (!enabled) {
            throw new IllegalStateException(
                    "MiniMax provider is not enabled. Set the MINIMAX_API_KEY env var "
                            + "(or app.minimax.api-key) and app.minimax.enabled=true.");
        }
        if (apiKey.isBlank()) {
            throw new IllegalStateException("MINIMAX_API_KEY is not configured.");
        }

        ObjectNode body = mapper.createObjectNode();
        body.put("model", model);
        ArrayNode messages = body.putArray("messages");
        ObjectNode sys = messages.addObject();
        sys.put("role", "system");
        // The Topical Authority prompt is already a complete instruction set;
        // wrap it as a system message so the model treats it as policy.
        sys.put("content", "You are an assistant that follows the user's instructions exactly and replies only with valid JSON when asked.");
        ObjectNode user = messages.addObject();
        user.put("role", "user");
        user.put("content", prompt == null ? "" : prompt);

        // Pass temperature / format through. MiniMax's hosted API supports
        // response_format={"type":"json_object"} and temperature.
        if (options != null) {
            Object t = options.get("temperature");
            if (t instanceof Number n) body.put("temperature", n.doubleValue());
            if (Boolean.TRUE.equals(options.get("format"))) {
                ObjectNode rf = mapper.createObjectNode();
                rf.put("type", "json_object");
                body.set("response_format", rf);
            }
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(apiKey);

        HttpEntity<String> entity = new HttpEntity<>(mapper.writeValueAsString(body), headers);

        String url = baseUrl + "/v1/chat/completions";
        try {
            String response = restTemplate.postForObject(url, entity, String.class);
            if (response == null || response.isBlank()) {
                throw new RuntimeException("Empty response from MiniMax.");
            }
            JsonNode root = mapper.readTree(response);
            JsonNode content = root.path("choices").path(0).path("message").path("content");
            if (content.isMissingNode() || content.isNull()) {
                throw new RuntimeException("No message.content in MiniMax reply: " + response);
            }
            return content.asText();
        } catch (RestClientException e) {
            // Never include the request/response bodies — they may echo the prompt
            // but we still don't want to leak auth headers if the SDK ever adds them.
            throw new Exception("MiniMax request failed: " + e.getMessage(), e);
        }
    }
}
