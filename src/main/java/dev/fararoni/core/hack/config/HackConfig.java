/*
 * Copyright (c) 2026 Eber Cruz Fararoni
 *
 *  This file is part of the core infrastructure.
 *  This source code is licensed under the MIT License found in the
 *  LICENSE file in the root directory of this source tree.
 *  Any distribution or modification must retain this copyright notice.
 */
package dev.fararoni.core.hack.config;

/**
 * Immutable runtime configuration for Fara-Hack.
 *
 * <p>All values come from environment variables. Defaults are sensible
 * for a local demo with no external services configured.</p>
 *
 * @param httpPort               port for the REST + WebSocket server
 * @param natsUrl                NATS broker URL (default in-memory bus if blank)
 * @param pluginToken            shared secret for the plugin WebSocket bridge
 * @param githubToken            optional GitHub PAT for the github MCP bridge
 * @param githubRepo             target repo for issue creation (owner/repo)
 * @param slackBotToken          optional Slack bot token
 * @param slackChannel           Slack channel for notifications
 * @param mcpFsSandbox           path used by the filesystem MCP bridge
 * @param eshopRepoPath          read-only mount of the eShop repo for Code Surgeon
 * @param mitigationEnabled      activate Step 6 (Sentinel Mitigation Proposal)
 * @param openAiCompatBaseUrl    OpenAI-compatible LLM endpoint (Ollama by default)
 * @param openAiCompatModel      model name to request
 * @param openAiCompatApiKey     optional API key (empty for local Ollama)
 *
 * @author Eber Cruz
 * @version 1.0.0
 * @since 1.0.0
 */
public record HackConfig(
        int httpPort,
        String natsUrl,
        String pluginToken,
        String githubToken,
        String githubRepo,
        String slackBotToken,
        String slackChannel,
        String mcpFsSandbox,
        String eshopRepoPath,
        boolean mitigationEnabled,
        String openAiCompatBaseUrl,
        String openAiCompatModel,
        String openAiCompatApiKey
) {

    /** Loads configuration from environment variables with sane defaults. */
    public static HackConfig fromEnvironment() {
        return new HackConfig(
                parsePort("HACK_HTTP_PORT", 8080),
                envOrDefault("NATS_URL", "nats://localhost:4222"),
                envOrDefault("FARARONI_PLUGIN_TOKEN", "demo-token-change-me"),
                envOrDefault("GITHUB_TOKEN", ""),
                envOrDefault("GITHUB_REPO", "ebercruz/fara-hack-demo"),
                envOrDefault("SLACK_BOT_TOKEN", ""),
                envOrDefault("SLACK_CHANNEL", "#engineering"),
                envOrDefault("MCP_FS_SANDBOX", "/tmp/fara-hack-sandbox"),
                envOrDefault("ESHOP_REPO_PATH", "./mock-eshop"),
                Boolean.parseBoolean(envOrDefault("MITIGATION_PROPOSAL_ENABLED", "true")),
                envOrDefault("OPENAI_COMPAT_BASE_URL", "http://localhost:11434/v1"),
                envOrDefault("OPENAI_COMPAT_MODEL", "llama3.2:3b"),
                envOrDefault("OPENAI_COMPAT_API_KEY", "")
        );
    }

    /** True if a real GitHub token is configured (not blank). */
    public boolean hasGitHub() {
        return githubToken != null && !githubToken.isBlank();
    }

    /** True if a real Slack bot token is configured. */
    public boolean hasSlack() {
        return slackBotToken != null && !slackBotToken.isBlank();
    }

    /** True if a real LLM endpoint is reachable (best-effort, not validated here). */
    public boolean hasLlm() {
        return openAiCompatBaseUrl != null && !openAiCompatBaseUrl.isBlank();
    }

    // ─── helpers ───────────────────────────────────────────────────────

    private static String envOrDefault(String key, String fallback) {
        final String value = System.getenv(key);
        return (value == null || value.isBlank()) ? fallback : value;
    }

    private static int parsePort(String key, int fallback) {
        try {
            return Integer.parseInt(envOrDefault(key, Integer.toString(fallback)));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
