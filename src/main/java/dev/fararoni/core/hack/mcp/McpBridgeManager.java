/*
 * Copyright (c) 2026 Eber Cruz Fararoni
 *
 *  This file is part of the core infrastructure.
 *  This source code is licensed under the MIT License found in the
 *  LICENSE file in the root directory of this source tree.
 *  Any distribution or modification must retain this copyright notice.
 */
package dev.fararoni.core.hack.mcp;

import dev.fararoni.core.hack.config.HackConfig;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Orchestrates the three {@link MinimalMcpBridge} instances used by the
 * triage pipeline:
 *
 * <ol>
 *   <li><b>github</b> — primary ticketing target (created issues), only
 *       enabled if {@code GITHUB_TOKEN} is set</li>
 *   <li><b>slack</b> — team notifications (Step 4), only enabled if
 *       {@code SLACK_BOT_TOKEN} is set</li>
 *   <li><b>filesystem</b> — always-on fallback. Tickets and notifications
 *       that cannot reach the cloud bridges are written here as JSON
 *       files for the demo</li>
 * </ol>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * McpBridgeManager mgr = new McpBridgeManager(config);
 * mgr.startAll();
 * mgr.bridge("github").ifPresent(b -> b.send(jsonRpcRequest));
 * mgr.stopAll();
 * }</pre>
 *
 * @author Eber Cruz
 * @version 1.0.0
 * @since 1.0.0
 */
public final class McpBridgeManager {

    private static final Logger LOG = Logger.getLogger(McpBridgeManager.class.getName());

    public static final String BRIDGE_GITHUB = "github";
    public static final String BRIDGE_SLACK  = "slack";
    public static final String BRIDGE_FS     = "filesystem";

    private final HackConfig config;
    private final Map<String, MinimalMcpBridge> bridges = new LinkedHashMap<>();

    public McpBridgeManager(HackConfig config) {
        this.config = config;
    }

    /** Spawn the bridges that have credentials configured + the always-on filesystem one. */
    public void startAll() {
        // Filesystem bridge — always
        registerSafely(BRIDGE_FS, new String[]{
                "npx", "-y", "@modelcontextprotocol/server-filesystem", config.mcpFsSandbox()
        });

        // GitHub bridge — only if token configured
        if (config.hasGitHub()) {
            registerSafely(BRIDGE_GITHUB, new String[]{
                    "npx", "-y", "@modelcontextprotocol/server-github"
            });
        } else {
            LOG.info("[MCP-MGR] Skipping github bridge (no GITHUB_TOKEN). "
                    + "Tickets will fall back to filesystem.");
        }

        // Slack bridge — only if token configured
        if (config.hasSlack()) {
            registerSafely(BRIDGE_SLACK, new String[]{
                    "npx", "-y", "@modelcontextprotocol/server-slack"
            });
        } else {
            LOG.info("[MCP-MGR] Skipping slack bridge (no SLACK_BOT_TOKEN). "
                    + "Notifications will fall back to filesystem.");
        }

        LOG.info("[MCP-MGR] Active bridges: " + bridges.keySet());
    }

    /** Returns the bridge by id, or empty if not registered. */
    public Optional<MinimalMcpBridge> bridge(String id) {
        return Optional.ofNullable(bridges.get(id));
    }

    /**
     * Returns the best available bridge for ticketing: github if running,
     * otherwise filesystem fallback.
     */
    public Optional<MinimalMcpBridge> ticketingBridge() {
        return bridge(BRIDGE_GITHUB).filter(MinimalMcpBridge::isAlive)
                .or(() -> bridge(BRIDGE_FS));
    }

    /**
     * Returns the best available bridge for team notifications: slack if
     * running, otherwise filesystem fallback.
     */
    public Optional<MinimalMcpBridge> notificationBridge() {
        return bridge(BRIDGE_SLACK).filter(MinimalMcpBridge::isAlive)
                .or(() -> bridge(BRIDGE_FS));
    }

    /** Stop all bridges (idempotent). */
    public void stopAll() {
        bridges.values().forEach(b -> {
            try {
                b.stop();
            } catch (Exception e) {
                LOG.log(Level.WARNING, "stop failed for " + b.instanceId(), e);
            }
        });
        bridges.clear();
    }

    public Map<String, MinimalMcpBridge> all() {
        return Map.copyOf(bridges);
    }

    // ─── internals ─────────────────────────────────────────────────────

    private void registerSafely(String id, String[] command) {
        final MinimalMcpBridge bridge = new MinimalMcpBridge(id, command);
        try {
            bridge.start();
            bridges.put(id, bridge);
        } catch (Exception e) {
            LOG.log(Level.WARNING, "[MCP-MGR] Failed to start bridge " + id
                    + " — continuing in degraded mode", e);
        }
    }
}
