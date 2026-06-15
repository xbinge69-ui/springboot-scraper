package com.example.scraper.service;

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

/**
 * Thin wrapper around Anthropic's <a href="https://docs.anthropic.com/en/api/messages">Messages
 * API</a>. Used by {@link AddVideoEntryPropagationService} to invoke the
 * {@code add-video-entry} Claude skill (loaded as a system prompt) on
 * each chunk of batch results.
 *
 * <p>Wire-compatible with Anthropic's {@code POST /v1/messages} schema.
 * We send {@code x-api-key: <key>} (NOT Bearer — Anthropic rejects Bearer
 * auth) plus {@code anthropic-version: 2023-06-01} and the standard
 * JSON content type. The system prompt is sent as a top-level
 * {@code system} field, not as a message in the {@code messages} array.
 *
 * <h3>Credential handling</h3>
 * The API key is read <strong>only</strong> from the {@code ANTHROPIC_API_KEY}
 * environment variable (or {@code app.add-video-entry.api-key} if you
 * really must, for local dev). It is never logged, never returned in
 * error messages, and never persisted. Use a placeholder / env var in
 * source.
 *
 * <h3>Pattern</h3>
 * Mirrors {@link com.example.scraper.service.llm.MinimaxChatProvider}
 * line-for-line: same {@code RestTemplate} + {@code SimpleClientHttpRequestFactory}
 * setup, same {@code @Value} injection style, same error-surfacing style.
 */
@Service
public class AnthropicMessagesClient {

    private static final Logger log = LoggerFactory.getLogger(AnthropicMessagesClient.class);

    private static final String ANTHROPIC_BASE_URL = "https://api.anthropic.com";
    private static final String ANTHROPIC_VERSION = "2023-06-01";

    private final RestTemplate restTemplate;
    private final ObjectMapper mapper = new ObjectMapper();
    private final String apiKey;
    private final String model;
    private final long timeoutSeconds;
    private final boolean enabled;

    public AnthropicMessagesClient(
            @Value("${ANTHROPIC_API_KEY:}") String envKey,
            @Value("${app.add-video-entry.api-key:}") String propsKey,
            @Value("${app.add-video-entry.model:claude-sonnet-4-5}") String model,
            @Value("${app.add-video-entry.timeout-seconds:300}") long timeoutSeconds,
            @Value("${app.add-video-entry.enabled:false}") boolean enabled) {
        // Prefer the env var. Fall back to property. Either blank → disabled.
        String key = (envKey != null && !envKey.isBlank()) ? envKey : propsKey;
        this.apiKey = (key == null) ? "" : key.trim();
        this.model = (model == null || model.isBlank()) ? "claude-sonnet-4-5" : model.trim();
        this.timeoutSeconds = timeoutSeconds > 0 ? timeoutSeconds : 300;
        this.enabled = enabled && !this.apiKey.isBlank();

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) Duration.ofSeconds(Math.min(this.timeoutSeconds, 30)).toMillis());
        factory.setReadTimeout((int) Duration.ofSeconds(this.timeoutSeconds).toMillis());
        this.restTemplate = new RestTemplate(factory);

        if (this.enabled) {
            log.info("AnthropicMessagesClient: enabled, model={}, timeout={}s", this.model, this.timeoutSeconds);
        } else {
            log.info("AnthropicMessagesClient: disabled (no ANTHROPIC_API_KEY set or app.add-video-entry.enabled=false)");
        }
        if (!this.apiKey.isBlank()) {
            String prefix = this.apiKey.length() >= 8
                    ? this.apiKey.substring(0, Math.min(8, this.apiKey.length())) + "..."
                    : this.apiKey;
            // Anthropic's hosted API issues keys prefixed with `sk-ant-`.
            // We WARN — the key still loads in case the user has a valid
            // non-standard key from a private deployment.
            if (this.apiKey.startsWith("sk-ant-")) {
                log.info("Anthropic key format: starts with 'sk-ant-' (looks valid)");
            } else {
                log.warn("Anthropic key format: starts with '{}' — Anthropic dashboard keys "
                        + "are usually prefixed with 'sk-ant-'. If the request fails with 401, "
                        + "regenerate the key in https://console.anthropic.com.", prefix);
            }
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public String getModel() {
        return model;
    }

    /**
     * Send a system prompt + a JSON-serialized chunk of entries to the
     * Anthropic Messages API and return the raw text of the first
     * content block. The caller is responsible for parsing the text
     * (we don't try to deserialize it here because the skill's
     * instruction is "reply with valid JSON" and the model might add
     * markdown fences).
     *
     * @param systemPrompt the add-video-entry skill (or any system prompt)
     * @param userJsonText the user message — typically a JSON-serialized
     *                     array of raw VideoCatalogEntry-shaped maps
     * @return the raw {@code content[0].text} of the first response
     * @throws IllegalStateException if the client is not enabled
     */
    public String sendMessage(String systemPrompt, String userJsonText) throws Exception {
        if (!enabled) {
            throw new IllegalStateException(
                    "Anthropic client is not enabled. Set the ANTHROPIC_API_KEY env var "
                            + "(or app.add-video-entry.api-key) and app.add-video-entry.enabled=true.");
        }
        if (apiKey.isBlank()) {
            throw new IllegalStateException("ANTHROPIC_API_KEY is not configured.");
        }

        ObjectNode body = mapper.createObjectNode();
        body.put("model", model);
        body.put("max_tokens", 8192);
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            body.put("system", systemPrompt);
        }
        ArrayNode messages = body.putArray("messages");
        ObjectNode user = messages.addObject();
        user.put("role", "user");
        user.put("content", userJsonText == null ? "" : userJsonText);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        // NOT Bearer — Anthropic explicitly rejects Bearer auth on this endpoint.
        headers.set("x-api-key", apiKey);
        headers.set("anthropic-version", ANTHROPIC_VERSION);

        HttpEntity<String> entity = new HttpEntity<>(mapper.writeValueAsString(body), headers);
        String url = ANTHROPIC_BASE_URL + "/v1/messages";

        try {
            org.springframework.http.ResponseEntity<String> resp = restTemplate.exchange(
                    url, org.springframework.http.HttpMethod.POST, entity, String.class);
            String response = resp.getBody();
            int status = resp.getStatusCode().value();
            if (response == null || response.isBlank()) {
                throw new RuntimeException("Empty response from Anthropic (HTTP " + status + ").");
            }
            if (status < 200 || status >= 300) {
                // Surface Anthropic's error envelope verbatim so debuggability
                // matches the Minimax provider.
                throw new RuntimeException("Anthropic HTTP " + status + ": " + response);
            }
            JsonNode root = mapper.readTree(response);
            // Anthropic returns {"content":[{"type":"text","text":"..."}], ...}.
            // When the model refuses, content may be missing or contain a
            // refusal block; surface a useful error in that case.
            JsonNode content = root.path("content");
            if (!content.isArray() || content.isEmpty()) {
                String stopReason = root.path("stop_reason").asText("unknown");
                throw new RuntimeException("No content in Anthropic reply (stop_reason=" + stopReason + "): " + response);
            }
            JsonNode firstBlock = content.get(0);
            String type = firstBlock.path("type").asText("");
            if (!"text".equals(type)) {
                // e.g. "tool_use" — we don't send any tools, so this would be unexpected
                throw new RuntimeException("Anthropic reply first block is type='" + type
                        + "', expected 'text'. Full response: " + response);
            }
            String text = firstBlock.path("text").asText("");
            if (text.isBlank()) {
                throw new RuntimeException("Anthropic returned blank text content: " + response);
            }
            // Surface a stop_reason warning for the log.
            String stopReason = root.path("stop_reason").asText("");
            if (!"end_turn".equals(stopReason) && !stopReason.isEmpty()) {
                log.warn("Anthropic stop_reason={} (not 'end_turn') — model may have been "
                        + "truncated by max_tokens. Consider raising max_tokens or chunking "
                        + "smaller.", stopReason);
            }
            return text;
        } catch (RestClientException e) {
            throw new Exception("Anthropic request failed: " + e.getMessage(), e);
        }
    }

    /**
     * Cheap connectivity probe used by future diagnostic endpoints.
     * Sends a 1-token request so the user can see Anthropic's actual
     * auth verdict without having to run curl by hand. Returns a
     * structured result so the UI can render the exact status code +
     * message.
     */
    public PingResult ping() {
        if (!enabled || apiKey.isBlank()) {
            return new PingResult(false, 0, apiKey.isBlank()
                    ? "no API key (set ANTHROPIC_API_KEY or app.add-video-entry.api-key)"
                    : "app.add-video-entry.enabled=false",
                    "", 0, "model=" + model);
        }
        ObjectNode body = mapper.createObjectNode();
        body.put("model", model);
        body.put("max_tokens", 16);
        ArrayNode messages = body.putArray("messages");
        ObjectNode user = messages.addObject();
        user.put("role", "user");
        user.put("content", "ping");

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("x-api-key", apiKey);
        headers.set("anthropic-version", ANTHROPIC_VERSION);

        String url = ANTHROPIC_BASE_URL + "/v1/messages";
        long started = System.currentTimeMillis();
        try {
            org.springframework.http.ResponseEntity<String> resp = restTemplate.exchange(
                    url, org.springframework.http.HttpMethod.POST,
                    new HttpEntity<>(mapper.writeValueAsString(body), headers), String.class);
            int status = resp.getStatusCode().value();
            String response = resp.getBody();
            long latencyMs = System.currentTimeMillis() - started;
            if (response == null) response = "";
            JsonNode root = mapper.readTree(response);
            String snippet = root.path("content").path(0).path("text").asText("");
            String msg = status >= 200 && status < 300 ? "ok" : ("http " + status);
            return new PingResult(status >= 200 && status < 300, status, msg, snippet, latencyMs,
                    "model=" + model);
        } catch (Exception e) {
            long latencyMs = System.currentTimeMillis() - started;
            return new PingResult(false, 0, e.getClass().getSimpleName() + ": " + e.getMessage(),
                    "", latencyMs, "model=" + model);
        }
    }

    /** Diagnostic record returned by {@link #ping()}. */
    public record PingResult(
            boolean ok,
            int httpStatus,
            String message,
            String replySnippet,
            long latencyMs,
            String context
    ) {}
}
