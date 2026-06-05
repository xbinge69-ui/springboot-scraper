package com.example.scraper.service.llm;

import java.util.Map;

/**
 * Strategy interface for the LLM back-end used by the Topical Authority
 * enrichment step. Two implementations exist:
 * <ul>
 *   <li>{@code OllamaProvider} — local Ollama HTTP server (the default).</li>
 *   <li>{@code MinimaxChatProvider} — MiniMax's hosted OpenAI-compatible API,
 *       selected when the pipeline page checkbox is on.</li>
 * </ul>
 *
 * <p>Both return raw model output text. The JSON parsing is the same
 * for every back-end, so the orchestrator can swap providers without
 * touching the prompt or the response parser.
 */
public interface LlmProvider {

    /** Human-readable name (e.g. "ollama", "minimax-chat"). */
    String name();

    /**
     * Run the prompt and return the model's raw text reply.
     *
     * @param prompt  the user prompt
     * @param options model-options bag — at minimum the orchestrator passes
     *                {@code format=json} and {@code temperature=0.2}. Providers
     *                that don't support a given key (e.g. some hosted APIs
     *                ignore {@code format}) are free to drop it.
     */
    String generate(String prompt, Map<String, Object> options) throws Exception;

    /** Quick liveness probe. Used by the dashboard to render provider state. */
    boolean isAvailable();
}
