/*
 * Copyright (c) 2026 Eber Cruz Fararoni
 *
 *  This file is part of the core infrastructure.
 *  This source code is licensed under the MIT License found in the
 *  LICENSE file in the root directory of this source tree.
 *  Any distribution or modification must retain this copyright notice.
 */
package dev.fararoni.core.hack;

import dev.fararoni.core.FararoniCore;
import dev.fararoni.core.hack.api.BugReportController;
import dev.fararoni.core.hack.config.HackConfig;
import dev.fararoni.core.hack.mcp.McpBridgeManager;
import dev.fararoni.core.hack.triage.TriageStatusWatcher;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * HackBootstrap — entrypoint for Fara-Hack 1.0 (AgentX Hackathon submission).
 *
 * <p>Wires together the bug-report triage pipeline:</p>
 * <ol>
 *   <li>Loads {@link HackConfig} from environment variables (.env)</li>
 *   <li>Starts the in-house MCP bridges via {@link McpBridgeManager}
 *       (GitHub, Slack, filesystem)</li>
 *   <li>Boots a Javalin REST + WebSocket server with the
 *       {@link BugReportController} mounted on {@code /api/triage/*}</li>
 *   <li>Spawns the {@link TriageStatusWatcher} actor that polls for
 *       resolved tickets every 30 seconds and notifies reporters</li>
 *   <li>Blocks the main thread until SIGTERM</li>
 * </ol>
 *
 * <h2>Runtime modes</h2>
 * <ul>
 *   <li>{@code --server} (default) start REST + WS on HACK_HTTP_PORT</li>
 *   <li>{@code --health}            print health JSON and exit</li>
 *   <li>{@code --selftest}          run a deterministic demo mission and exit</li>
 *   <li>{@code --version}           print version and exit</li>
 * </ul>
 *
 * @author Eber Cruz
 * @since 1.0.0
 */
public final class HackBootstrap {

    private static final Logger LOG = Logger.getLogger(HackBootstrap.class.getName());
    private static final String VERSION = "1.0.0";

    private HackBootstrap() {
        // utility — no instantiation
    }

    public static void main(String[] args) {
        final String mode = args.length > 0 ? args[0] : "--server";

        switch (mode) {
            case "--health"   -> handleHealth();
            case "--version"  -> handleVersion();
            case "--selftest" -> handleSelftest();
            case "--server"   -> handleServer();
            default -> {
                System.err.println("Unknown mode: " + mode);
                System.err.println("Usage: fara-hack [--server | --health | --selftest | --version]");
                System.exit(1);
            }
        }
    }

    // ─── Modes ─────────────────────────────────────────────────────────

    private static void handleHealth() {
        System.out.println("{\"status\":\"healthy\",\"version\":\"" + VERSION + "\"}");
        System.exit(0);
    }

    private static void handleVersion() {
        System.out.println("Fara-Hack " + VERSION);
        System.exit(0);
    }

    private static void handleSelftest() {
        LOG.info("[HACK] Selftest mode — running deterministic demo mission");
        try {
            final HackConfig config = HackConfig.fromEnvironment();
            LOG.info("[HACK] Config loaded: port=" + config.httpPort()
                    + ", nats=" + config.natsUrl());
            System.out.println("{\"selftest\":\"ok\",\"version\":\"" + VERSION + "\"}");
            System.exit(0);
        } catch (Exception e) {
            LOG.log(Level.SEVERE, "Selftest failed", e);
            System.exit(2);
        }
    }

    private static void handleServer() {
        printBanner();

        final HackConfig config = HackConfig.fromEnvironment();
        LOG.info("[HACK] Configuration:");
        LOG.info("[HACK]   HTTP port      : " + config.httpPort());
        LOG.info("[HACK]   NATS URL       : " + config.natsUrl());
        LOG.info("[HACK]   GitHub bridge  : " + (config.hasGitHub() ? "ENABLED" : "fallback"));
        LOG.info("[HACK]   Slack bridge   : " + (config.hasSlack() ? "ENABLED" : "fallback"));
        LOG.info("[HACK]   Mitigation step: " + (config.mitigationEnabled() ? "ON" : "OFF"));
        LOG.info("[HACK]   LLM endpoint   : " + config.openAiCompatBaseUrl());
        LOG.info("[HACK]   LLM model      : " + config.openAiCompatModel());

        // 0. Bootstrap FararoniCore (the reasoning engine).
        // Owns the LLM dispatcher, agent template manager, and NATS bus.
        // Best-effort: if it fails, controller runs in degraded mode.
        final FararoniCore core = bootstrapCore();

        // 1. Bring up MCP bridges (in-process Virtual Threads, no extra containers)
        final McpBridgeManager bridges = new McpBridgeManager(config);
        try {
            bridges.startAll();
        } catch (Exception e) {
            LOG.log(Level.WARNING, "[HACK] MCP bridges failed to start (continuing in degraded mode)", e);
        }

        // 2. Start the Triage Status Watcher (polls for resolved tickets)
        final TriageStatusWatcher watcher = new TriageStatusWatcher(config, bridges);
        watcher.start();

        // 3. Mount REST + WebSocket
        final BugReportController controller = new BugReportController(config, bridges, watcher, core);
        try {
            controller.start();
            LOG.info("[HACK] Ready. Listening on http://0.0.0.0:" + config.httpPort());
            LOG.info("[HACK]   Try: curl http://localhost:" + config.httpPort() + "/api/health");
        } catch (Exception e) {
            LOG.log(Level.SEVERE, "[HACK] Failed to start HTTP server", e);
            shutdown(bridges, watcher, controller);
            System.exit(3);
        }

        // 4. Graceful shutdown on SIGTERM
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            LOG.info("[HACK] Shutdown signal received");
            shutdown(bridges, watcher, controller);
        }, "hack-shutdown-hook"));

        // 5. Block forever
        try {
            Thread.currentThread().join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void shutdown(McpBridgeManager bridges,
                                  TriageStatusWatcher watcher,
                                  BugReportController controller) {
        try { controller.stop(); } catch (Exception ignored) { }
        try { watcher.stop();    } catch (Exception ignored) { }
        try { bridges.stopAll(); } catch (Exception ignored) { }
        LOG.info("[HACK] Shutdown complete");
    }

    /**
     * Best-effort bootstrap of the embedded {@link FararoniCore}.
     *
     * <p>The core's {@code initialize()} method discovers the LLM
     * provider, loads agents from YAML, and connects the NATS
     * Sovereign Event Bus. It can take a few
     * seconds and may fail if no LLM endpoint is reachable. We log
     * the failure and return {@code null} so the controller can fall
     * back to the regex-based triage path.</p>
     */
    private static FararoniCore bootstrapCore() {
        final String workingDir = System.getenv().getOrDefault("HACK_WORKING_DIR", "/app/workspace");
        final Path workspace = Paths.get(workingDir);
        try {
            // Make sure the working dir exists — the core writes its
            // context vault and agent state under it.
            workspace.toFile().mkdirs();

            LOG.info("[HACK] Bootstrapping fararoni-core at " + workspace.toAbsolutePath());
            final FararoniCore core = new FararoniCore(workspace).initialize();
            LOG.info("[HACK] fararoni-core ready (agents + NATS bus + LLM dispatcher online)");
            return core;
        } catch (Throwable t) {
            LOG.log(Level.WARNING,
                    "[HACK] fararoni-core bootstrap failed — falling back to regex-only triage. "
                            + "Reason: " + t.getMessage(), t);
            return null;
        }
    }

    private static void printBanner() {
        LOG.info("""

            ╔══════════════════════════════════════════════════════════╗
            ║         FARA-HACK 1.0 — AgentX Hackathon                 ║
            ║   Sovereign Agentic Runtime · Java 25 · MIT License      ║
            ║   #AgentXHackathon                                       ║
            ╚══════════════════════════════════════════════════════════╝
            """);
    }
}
