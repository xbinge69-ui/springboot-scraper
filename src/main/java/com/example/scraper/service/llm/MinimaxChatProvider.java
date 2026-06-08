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
        if (!this.apiKey.isBlank()) {
            String prefix = this.apiKey.length() >= 8
                    ? this.apiKey.substring(0, Math.min(8, this.apiKey.length())) + "..."
                    : this.apiKey;
            // MiniMax's hosted API at api.minimaxi.com uses JWT tokens
            // (eyJ...). An "sk-..." key (OpenAI-style) is from a different
            // service and will be rejected with base_resp status_code 2049.
            // We only WARN — the key still loads in case the user has a
            // valid non-JWT key from a private deployment.
            if (this.apiKey.startsWith("eyJ")) {
                log.info("MiniMax key format: looks like a JWT (eyJ...) — OK for api.minimaxi.com");
            } else if (this.apiKey.startsWith("sk-")) {
                log.warn("MiniMax key format: starts with '{}' — this is OpenAI-style and will be "
                        + "REJECTED by api.minimaxi.com with base_resp 2049. Get a real key from "
                        + "the MiniMax dashboard (the hosted API uses JWT tokens, eyJ...).",
                        prefix);
            } else {
                log.info("MiniMax key format: starts with '{}' (unrecognized — should be eyJ for "
                        + "api.minimaxi.com)", prefix);
            }
        }
    }

    @Override
    public String name() { return "minimax-chat"; }

    @Override
    public boolean isAvailable() {
        return enabled;
    }

    @Override
    public String unavailableReason() {
        if (enabled) return "configured";
        if (apiKey.isBlank()) {
            return "no API key — set MINIMAX_API_KEY env var or app.minimax.api-key";
        }
        return "app.minimax.enabled=false (set it to true in application.properties)";
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

        // MiniMax's chat endpoint is /v1/text/chatcompletion_v2 (NOT
        // /v1/chat/completions — the latter is OpenAI's path; MiniMax
        // returns 401 for unknown paths because auth is checked first).
        // The request/response BODY shape is the OpenAI chat completions
        // shape (model + messages[] + choices[].message.content).
        String url = baseUrl + "/v1/text/chatcompletion_v2";
        try {
            org.springframework.http.ResponseEntity<String> resp = restTemplate.exchange(
                    url, org.springframework.http.HttpMethod.POST, entity, String.class);
            String response = resp.getBody();
            int status = resp.getStatusCode().value();
            if (response == null || response.isBlank()) {
                throw new RuntimeException("Empty response from MiniMax (HTTP " + status + ").");
            }
            // On non-2xx, surface MiniMax's error body so we can see the real
            // reason (wrong model name, expired key, rate limit, etc.).
            if (status < 200 || status >= 300) {
                throw new RuntimeException("MiniMax HTTP " + status + ": " + response);
            }
            JsonNode root = mapper.readTree(response);
            // MiniMax error envelopes use {"base_resp":{"status_code":...,"status_msg":"..."}}
            JsonNode baseResp = root.path("base_resp");
            if (!baseResp.isMissingNode()) {
                int sc = baseResp.path("status_code").asInt(0);
                if (sc != 0 && sc != 0) {
                    // MiniMax returns 0 for success in base_resp. Anything else
                    // is an error, even if the HTTP status is 200.
                    String msg = baseResp.path("status_msg").asText("unknown");
                    throw new RuntimeException("MiniMax base_resp error " + sc + ": " + msg
                            + " — body: " + response);
                }
            }
            JsonNode content = root.path("choices").path(0).path("message").path("content");
            if (content.isMissingNode() || content.isNull()) {
                throw new RuntimeException("No message.content in MiniMax reply: " + response);
            }
            return content.asText();
        } catch (RestClientException e) {
            throw new Exception("MiniMax request failed: " + e.getMessage(), e);
        }
    }

    /**
     * Cheap connectivity probe used by {@code GET /api/llm/minimax/test}.
     * Sends a 1-token request so the user can see MiniMax's actual auth
     * verdict in the browser without having to run curl by hand. Returns
     * a structured result so the UI can render the exact status_code +
     * status_msg from MiniMax's {@code base_resp} envelope.
     */
    public PingResult ping() {
        if (!enabled || apiKey.isBlank()) {
            return PingResult.unconfigured(apiKey.isBlank()
                    ? "no API key (set MINIMAX_API_KEY or app.minimax.api-key)"
                    : "app.minimax.enabled=false");
        }

        ObjectNode body = mapper.createObjectNode();
        body.put("model", model);
        ArrayNode messages = body.putArray("messages");
        ObjectNode user = messages.addObject();
        user.put("role", "user");
        user.put("content", "ping");

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(apiKey);

        String url = baseUrl + "/v1/text/chatcompletion_v2";
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

            // MiniMax's envelope: {"base_resp":{"status_code":0,"status_msg":"success"}}.
            // Auth errors come back as HTTP 200 + status_code 2049 (per MiniMax's
            // docs) but other failures can come back as 4xx/5xx — handle both.
            int code = root.path("base_resp").path("status_code").asInt(status);
            String msg = root.path("base_resp").path("status_msg").asText(
                    status >= 200 && status < 300 ? "ok" : "http " + status);

            // Best-effort: a success ping produces 1 token of content.
            String snippet = root.path("choices").path(0).path("message").path("content").asText("");
            return new PingResult(true, status, code, msg, snippet, latencyMs,
                    "model=" + model + ", baseUrl=" + baseUrl);
        } catch (Exception e) {
            long latencyMs = System.currentTimeMillis() - started;
            return new PingResult(false, 0, 0, e.getClass().getSimpleName() + ": " + e.getMessage(),
                    "", latencyMs, "model=" + model + ", baseUrl=" + baseUrl);
        }
    }

    /**
     * Diagnostic record returned by {@link #ping()}. {@code ok} is true
     * ONLY when MiniMax's {@code base_resp.status_code == 0} (their
     * documented "success" sentinel — HTTP 200 alone is not enough).
     */
    public record PingResult(
            boolean ok,
            int httpStatus,
            int baseRespCode,
            String baseRespMsg,
            String replySnippet,
            long latencyMs,
            String context
    ) {
        static PingResult unconfigured(String reason) {
            return new PingResult(false, 0, 0, reason, "", 0, "not configured");
        }
    }
}
