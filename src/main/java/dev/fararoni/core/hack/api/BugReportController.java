/*
 * Copyright (c) 2026 Eber Cruz Fararoni
 *
 *  This file is part of the core infrastructure.
 *  This source code is licensed under the MIT License found in the
 *  LICENSE file in the root directory of this source tree.
 *  Any distribution or modification must retain this copyright notice.
 */
package dev.fararoni.core.hack.api;

import dev.fararoni.core.FararoniCore;
import dev.fararoni.core.core.commands.DirectAgentExecutor;
import dev.fararoni.core.core.mission.engine.AgentTemplateManager;
import dev.fararoni.core.core.mission.model.AgentTemplate;
import dev.fararoni.bus.agent.api.bus.SovereignEnvelope;
import dev.fararoni.bus.agent.api.bus.SovereignEventBus;
import dev.fararoni.core.hack.config.HackConfig;
import dev.fararoni.core.hack.llm.LlmTriageClient;
import dev.fararoni.core.hack.llm.LlmTriageResult;
import dev.fararoni.core.hack.mcp.McpBridgeManager;
import dev.fararoni.core.hack.sentinel.SentinelDiffAdapter;
import dev.fararoni.core.hack.triage.BugReport;
import dev.fararoni.core.hack.triage.Ticket;
import dev.fararoni.core.hack.triage.TriageStatusWatcher;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.javalin.Javalin;
import io.javalin.websocket.WsContext;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * BugReportController — implements <b>Steps 1-4 + 6</b> of the AgentX
 * pipeline. Owns the public REST + WebSocket surface that the Angular
 * frontend (or any curl client) talks to.
 *
 * <h2>Endpoints</h2>
 * <ul>
 *   <li>{@code GET  /api/health}                          — liveness probe</li>
 *   <li>{@code GET  /api/version}                         — version JSON</li>
 *   <li>{@code POST /api/triage/report}                   — submit a bug report (Steps 1-4 + 6)</li>
 *   <li>{@code GET  /api/triage/tickets}                  — list created tickets</li>
 *   <li>{@code POST /api/triage/tickets/{id}/resolve}     — mark RESOLVED (triggers Step 5)</li>
 *   <li>{@code WS   /ws/events?correlationId=X}           — live reasoning trace</li>
 * </ul>
 *
 * <h2>Why Javalin (not com.sun.net.httpserver)</h2>
 * <p>The first iteration of this controller used the JDK built-in
 * {@code com.sun.net.httpserver}, which works for plain HTTP but does
 * not support WebSocket. The Angular frontend needs a WS endpoint to
 * stream the reasoning trace to the user, so we swap to Javalin 6.1
 * — same library used by {@link dev.fararoni.core.server.FararoniServer}
 * in {@code fararoni-core}, transitively available via that
 * dependency. Zero new external libraries.</p>
 *
 * <h2>Reasoning Trace streaming</h2>
 * <p>When a bug report is submitted, the controller fans out trace
 * events to every WebSocket subscribed to the {@code correlationId} of
 * that report. Subscribers register via
 * {@code GET /ws/events?correlationId=...}. Each step of the pipeline
 * (extract, classify, guardian-report, ticket-create, sentinel-verdict,
 * notify) emits one trace envelope with shape:
 * <pre>
 * {
 *   "type": "TRACE",
 *   "correlationId": "...",
 *   "step": "STEP_2_TRIAGE",
 *   "actor": "operations-analyst",
 *   "thought": "Extracted 3 file refs and 1 stack frame",
 *   "timestamp": "2026-04-08T12:34:56Z"
 * }
 * </pre>
 *
 * @author Eber Cruz
 * @version 1.0.0
 * @since 1.0.0
 */
public final class BugReportController {

    private static final Logger LOG = Logger.getLogger(BugReportController.class.getName());
    private static final Pattern EMAIL = Pattern.compile("^[\\w.!#$%&'*+/=?^`{|}~-]+@[\\w-]+(\\.[\\w-]+)+$");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HackConfig config;
    private final McpBridgeManager bridges;
    private final TriageStatusWatcher watcher;
    private final LlmTriageClient llm;
    private final FararoniCore core;

    /** correlationId → set of subscribed WebSocket contexts. */
    private final Map<String, Map<String, WsContext>> wsSessions = new ConcurrentHashMap<>();

    private Javalin app;

    public BugReportController(HackConfig config,
                                McpBridgeManager bridges,
                                TriageStatusWatcher watcher,
                                FararoniCore core) {
        this.config = config;
        this.bridges = bridges;
        this.watcher = watcher;
        this.llm = new LlmTriageClient(config);
        this.core = core;
        this.watcher.onTicketResolved(this::notifyReporter);
    }

    public void start() {
        // Defense in depth: even though the production path is same-origin
        // via the Nginx reverse proxy (browser → :8080/Nginx → fara-hack:8080),
        // we enable a permissive CORS rule so that:
        //   1. External smoke tests / curl / Postman work without surprises.
        //   2. Any preflight OPTIONS request is answered with 204 instead
        //      of the Javalin default 404 (which made smoke test §1 of
        //      docs/PENDIENTES-SMOKE-TEST-2026-04-08.md fail).
        //   3. A future frontend hosted on a different origin (e.g. for
        //      remote demos) can hit this API without code changes.
        // The real perimeter is Nginx; this rule does not weaken it.
        app = Javalin.create(cfg -> {
            cfg.showJavalinBanner = false;
            cfg.bundledPlugins.enableCors(cors -> cors.addRule(rule -> {
                rule.anyHost();
                rule.allowCredentials = false;
            }));
            // [2026-04-09] Allow multimodal POSTs with large screenshots.
            // Default is ~1 MB which blocked submissions of 1+ MB error
            // captures with HTTP 413. Same justification as the Nginx
            // client_max_body_size 10m on the reverse proxy: typical
            // user screenshots are 1-3 MB and base64 inflation pushes
            // them to ~4 MB on the wire.
            cfg.http.maxRequestSize = 10L * 1024 * 1024; // 10 MB
        });

        registerHttpRoutes();
        registerWebSocket();

        app.start("0.0.0.0", config.httpPort());
    }

    public void stop() {
        if (app != null) {
            app.stop();
        }
    }

    // ─── HTTP routes ───────────────────────────────────────────────────

    private void registerHttpRoutes() {
        app.get("/api/health", ctx ->
                ctx.json(Map.of("status", "healthy")));

        app.get("/api/version", ctx ->
                ctx.json(Map.of("version", "1.0.0", "name", "fara-hack")));

        app.post("/api/triage/report", ctx -> {
            final BugReport report;
            try {
                final JsonNode body = MAPPER.readTree(ctx.body());
                report = parseAndValidate(body);
            } catch (IllegalArgumentException e) {
                ctx.status(400).json(Map.of("error", e.getMessage()));
                return;
            } catch (Exception e) {
                ctx.status(400).json(Map.of("error", "invalid JSON"));
                return;
            }

            LOG.info(() -> "[API] received bug report from " + report.reporterEmail()
                    + " correlationId=" + report.correlationId());

            // Process asynchronously so the HTTP response returns immediately
            Thread.ofVirtual()
                    .name("triage-" + report.correlationId())
                    .start(() -> processReportAsync(report));

            final ObjectNode response = MAPPER.createObjectNode();
            response.put("status", "accepted");
            response.put("correlationId", report.correlationId());
            response.put("wsUrl", "/ws/events?correlationId=" + report.correlationId());
            ctx.status(202).json(response);
        });

        app.get("/api/triage/tickets", ctx -> {
            final ObjectNode root = MAPPER.createObjectNode();
            root.put("count", watcher.store().count());
            final var array = root.putArray("tickets");
            for (Ticket t : watcher.store().all()) {
                final ObjectNode node = array.addObject();
                node.put("ticketId", t.ticketId());
                node.put("title", t.title());
                node.put("state", t.state().name());
                node.put("severity", t.severity());
                node.put("assignedTo", t.assignedTo() == null ? "" : t.assignedTo());
                node.put("sentinelVerified", t.sentinelVerified());
                node.put("createdAt", t.createdAt().toString());
                node.put("ticketUrl", t.ticketUrl() == null ? "" : t.ticketUrl());
            }
            ctx.json(root);
        });

        app.post("/api/triage/tickets/{id}/resolve", ctx -> {
            final String ticketId = ctx.pathParam("id");
            final var updated = watcher.store().updateState(ticketId, Ticket.State.RESOLVED);
            if (updated.isEmpty()) {
                ctx.status(404).json(Map.of("error", "ticket not found"));
                return;
            }
            ctx.json(Map.of("status", "resolved", "ticketId", ticketId));
        });
    }

    // ─── WebSocket — Reasoning Trace stream ────────────────────────────

    private void registerWebSocket() {
        app.ws("/ws/events", ws -> {
            ws.onConnect(ctx -> {
                final String correlationId = ctx.queryParam("correlationId");
                if (correlationId == null || correlationId.isBlank()) {
                    ctx.closeSession(4001, "missing correlationId");
                    return;
                }
                // Extend the Jetty WebSocket idle timeout to 10 minutes so the
                // socket survives long LLM inferences (qwen3.5:35b-a3b can take
                // 30–120s per turn). The default 30s closed the connection
                // mid-mission and made smoke test §2 of PENDIENTES fail.
                try {
                    ctx.session.setIdleTimeout(Duration.ofMinutes(10));
                } catch (Exception e) {
                    LOG.warning("[WS] could not set idle timeout: " + e.getMessage());
                }
                wsSessions.computeIfAbsent(correlationId, k -> new ConcurrentHashMap<>())
                        .put(ctx.sessionId(), ctx);
                LOG.info(() -> "[WS] subscriber connected for " + correlationId
                        + " (sessionId=" + ctx.sessionId() + ")");
                emitTo(ctx, "CONNECTED", correlationId, "system",
                        "Live reasoning trace ready for " + correlationId);
            });

            ws.onClose(ctx -> {
                final String correlationId = ctx.queryParam("correlationId");
                if (correlationId != null) {
                    final var subs = wsSessions.get(correlationId);
                    if (subs != null) {
                        subs.remove(ctx.sessionId());
                        if (subs.isEmpty()) {
                            wsSessions.remove(correlationId);
                        }
                    }
                }
            });

            ws.onError(ctx -> LOG.warning("[WS] error: " + ctx.error()));
        });
    }

    /** Publish a trace envelope to all subscribers of a correlationId. */
    private void publishTrace(String correlationId, String step, String actor, String thought) {
        final var subs = wsSessions.get(correlationId);
        if (subs == null || subs.isEmpty()) {
            return;
        }
        final ObjectNode envelope = MAPPER.createObjectNode();
        envelope.put("type", "TRACE");
        envelope.put("correlationId", correlationId);
        envelope.put("step", step);
        envelope.put("actor", actor);
        envelope.put("thought", thought);
        envelope.put("timestamp", Instant.now().toString());
        final String json = envelope.toString();
        for (WsContext ctx : subs.values()) {
            try {
                if (ctx.session.isOpen()) {
                    ctx.send(json);
                }
            } catch (Exception ignored) { }
        }
    }

    private void emitTo(WsContext ctx, String type, String correlationId, String actor, String thought) {
        try {
            final ObjectNode envelope = MAPPER.createObjectNode();
            envelope.put("type", type);
            envelope.put("correlationId", correlationId);
            envelope.put("actor", actor);
            envelope.put("thought", thought);
            envelope.put("timestamp", Instant.now().toString());
            ctx.send(envelope.toString());
        } catch (Exception ignored) { }
    }

    // ─── pipeline core (steps 2 → 4 + 6) ───────────────────────────────

    /**
     * Runs the triage pipeline asynchronously and emits trace events
     * to all WS subscribers of the {@code correlationId}.
     */
    private void processReportAsync(BugReport report) {
        final String cid = report.correlationId();
        try {
            publishTrace(cid, "STEP_1_RECEIVED", "controller",
                    "Bug report received from " + report.reporterEmail()
                            + (report.hasAttachment()
                                    ? " (attachment: " + report.attachmentName() + ", "
                                            + report.attachmentMime() + ")"
                                    : ""));

            // ─── Step 0 — Vision Adapter (multimodal pre-processing) ──
            // When the report carries an image attachment, the vision-capable
            // LLM (qwen3.5:35b-a3b) produces a textual forensic description
            // injected into the payload for the 4-agent pipeline.
            String visionEvidence = "";
            LlmTriageResult llmResult = null;
            if (report.hasAttachment() && llm.isEnabled()) {
                publishTrace(cid, "STEP_0_VISION", "vision-adapter",
                        "Calling " + config.openAiCompatModel()
                                + " with text + " + report.attachmentMime()
                                + " (" + (report.attachmentBase64().length() / 1024) + "KB base64)");

                llmResult = llm.triage(
                        report,
                        report.attachmentBase64(),
                        report.attachmentMime()
                );

                if (llmResult.isReal()) {
                    visionEvidence = "\n\n---\nVISION FORENSIC EVIDENCE (from "
                            + llmResult.model() + ", "
                            + llmResult.latency().toMillis() + "ms, "
                            + "multimodal=" + llmResult.multimodal() + "):\n"
                            + llmResult.technicalSummary()
                            + (llmResult.suspectedRootCause().isBlank() ? "" :
                                    "\n\nSuspected root cause: " + llmResult.suspectedRootCause());
                    publishTrace(cid, "STEP_0_VISION", "vision-adapter",
                            "Vision result (severity=" + llmResult.severity()
                                    + ", confidence=" + String.format("%.2f", llmResult.confidence())
                                    + "): " + truncateForTrace(llmResult.technicalSummary()));
                } else {
                    publishTrace(cid, "STEP_0_VISION", "vision-adapter",
                            "Vision call failed: " + llmResult.technicalSummary()
                                    + " — proceeding with text-only reasoning");
                }
            } else if (report.hasAttachment()) {
                publishTrace(cid, "STEP_0_VISION", "vision-adapter",
                        "Attachment present but LLM not configured — skipping vision step");
            }

            // ─── Step 2-6 — 4-agent pipeline via DirectAgentExecutor ──
            // Orchestrated by the controller with NATS telemetry.
            // DirectAgentExecutor drives the LLM tool-calling loop for each
            // agent. The Sovereign Bus (NATS P:100) is used for telemetry
            // publishing so the pipeline is observable via bus subscribers.
            //
            // Pipeline: coordinator → (forensic ║ mitigation) → broker
            //
            // NOTE: Full bus-orchestrated missions (agency.mission.start →
            // SovereignMissionEngineV2) require a core fix for NATS
            // payload deserialization (LinkedHashMap vs typed record).
            // Tracked for V2. See docs/ANALYSIS-NATS-SOVEREIGN-TOOLING.md §3.
            publishTrace(cid, "STEP_2_REASONING", "triage-coordinator",
                    core != null
                            ? "Spawning 4-agent pipeline (Coordinator → Forensic ║ Mitigation → Broker)"
                            : "FararoniCore unavailable — using regex fallback");

            String severity;
            String summary;
            List<String> affectedModules;
            boolean sentinelVerified = false;
            String mitigationBlock = "";

            // Resolve NATS bus for telemetry (nullable — pipeline works without it)
            final SovereignEventBus bus = core != null ? core.getSovereignBus() : null;

            if (core != null) {
                final String coreInput = buildCorePrompt(report, visionEvidence);
                try {
                    final long t0 = System.currentTimeMillis();
                    final AgentTemplateManager atm = core.getAgentTemplateManager();
                    final DirectAgentExecutor exec = new DirectAgentExecutor(core.getLlmDispatcher());

                    // ── STEP 2: Triage Coordinator (sequential gate) ──
                    publishBusTelemetry(bus, cid, "triage-coordinator", "THINKING", "Processing bug report");
                    final String triageJson = runDirectAgent(
                            atm, exec, "triage-coordinator", coreInput);
                    publishTrace(cid, "STEP_2_REASONING", "triage-coordinator",
                            "Coordinator output (" + triageJson.length() + " chars)");
                    publishBusTelemetry(bus, cid, "triage-coordinator", "COMPLETED",
                            "Severity classified (" + triageJson.length() + " chars)");

                    // ── STEP 2.5 + 6: Forensic ║ Mitigation in parallel (Virtual Threads) ──
                    final String contextWithCoord = coreInput
                            + "\n\n=== Triage Coordinator output ===\n" + triageJson;
                    final AtomicReference<String> forensicRef = new AtomicReference<>("");
                    final AtomicReference<String> mitigationRef = new AtomicReference<>("");

                    publishTrace(cid, "STEP_2_5_FORENSIC", "forensic-analyst",
                            "Spawning parallel branch (forensic ║ mitigation) on Virtual Threads");
                    publishBusTelemetry(bus, cid, "forensic-analyst", "THINKING", "Analyzing duplicates + ownership");
                    publishBusTelemetry(bus, cid, "mitigation-engineer", "THINKING", "Generating patch proposal");

                    final Thread tForensic = Thread.ofVirtual()
                            .name("forensic-" + cid)
                            .start(() -> {
                                try {
                                    forensicRef.set(runDirectAgent(
                                            atm, exec, "forensic-analyst", contextWithCoord));
                                } catch (Exception ex) {
                                    forensicRef.set("{\"error\":\"" + ex.getMessage() + "\"}");
                                }
                            });
                    final Thread tMitigation = Thread.ofVirtual()
                            .name("mitigation-" + cid)
                            .start(() -> {
                                try {
                                    mitigationRef.set(runDirectAgent(
                                            atm, exec, "mitigation-engineer", contextWithCoord));
                                } catch (Exception ex) {
                                    mitigationRef.set("{\"error\":\"" + ex.getMessage() + "\"}");
                                }
                            });
                    tForensic.join();
                    tMitigation.join();

                    final String forensicJson = forensicRef.get();
                    final String mitigationJson = mitigationRef.get();
                    publishTrace(cid, "STEP_2_5_FORENSIC", "forensic-analyst",
                            "Forensic done (" + forensicJson.length() + " chars)");
                    publishTrace(cid, "STEP_6_MITIGATION", "mitigation-engineer",
                            "Mitigation done (" + mitigationJson.length() + " chars)");
                    publishBusTelemetry(bus, cid, "forensic-analyst", "COMPLETED",
                            "Forensic analysis done (" + forensicJson.length() + " chars)");
                    publishBusTelemetry(bus, cid, "mitigation-engineer", "COMPLETED",
                            "Patch proposal done (" + mitigationJson.length() + " chars)");

                    // ── STEP 6 audit — Sentinel verdict on the patch ──
                    if (config.mitigationEnabled()
                            && mitigationJson != null
                            && !mitigationJson.isBlank()) {
                        final String candidatePatch = extractUnifiedDiff(mitigationJson);
                        if (candidatePatch.isBlank()) {
                            publishTrace(cid, "STEP_6_MITIGATION", "sentinel-auditor",
                                    "No patch / graceful skip");
                        } else {
                            publishTrace(cid, "STEP_6_MITIGATION", "sentinel-auditor",
                                    "Auditing diff (" + candidatePatch.length() + " chars) on 6 dimensions");
                            final var verdict = SentinelDiffAdapter.audit(candidatePatch);
                            switch (verdict) {
                                case SentinelDiffAdapter.Verdict.Approved a -> {
                                    sentinelVerified = true;
                                    mitigationBlock = "\n\n## Sentinel-Verified Patch\n```diff\n"
                                            + candidatePatch + "\n```\n";
                                    publishTrace(cid, "STEP_6_MITIGATION", "sentinel-auditor",
                                            "APPROVED — " + a.linesChanged() + " lines, "
                                                    + a.filesTouched() + " files");
                                }
                                case SentinelDiffAdapter.Verdict.Rejected r -> {
                                    publishTrace(cid, "STEP_6_MITIGATION", "sentinel-auditor",
                                            "REJECTED — rule=" + r.rule()
                                                    + " reason=" + r.reason());
                                }
                            }
                        }
                    }

                    // ── STEP 3+4: Triage Broker ──
                    final String oncallEnv = System.getenv("ONCALL_EMAIL");
                    final String oncallEmail = (oncallEnv != null && !oncallEnv.isBlank())
                            ? oncallEnv
                            : report.reporterEmail();
                    final String mitigationSummary = sentinelVerified
                            ? "Sentinel-verified patch attached to ticket."
                            : "No patch proposed (or rejected by Sentinel). Manual investigation required.";

                    final String brokerInput = coreInput
                            + "\n\n=== Triage Coordinator output ===\n" + triageJson
                            + "\n\n=== Forensic Analyst output ===\n" + forensicJson
                            + "\n\n=== Mitigation summary ===\n" + mitigationSummary
                            + "\n\nONCALL_EMAIL: " + oncallEmail
                            + "\nREPORTER_EMAIL: " + report.reporterEmail()
                            + "\nCORRELATION_ID: " + cid;
                    publishBusTelemetry(bus, cid, "triage-broker", "THINKING", "Composing notification");
                    final String brokerJson = runDirectAgent(
                            atm, exec, "triage-broker", brokerInput);
                    publishTrace(cid, "STEP_4_NOTIFY", "triage-broker",
                            "Broker dispatched (" + brokerJson.length() + " chars)");
                    publishBusTelemetry(bus, cid, "triage-broker", "COMPLETED",
                            "Broker dispatched (" + brokerJson.length() + " chars)");

                    final long elapsed = System.currentTimeMillis() - t0;
                    publishTrace(cid, "STEP_2_REASONING", "fara-hack",
                            "4-agent pipeline complete in " + elapsed + "ms");

                    summary = truncateForTicket(
                            "[Triage Coordinator]\n" + triageJson
                                    + "\n\n[Forensic Analyst]\n" + forensicJson
                                    + "\n\n[Mitigation Engineer]\n" + mitigationJson
                                    + "\n\n[Triage Broker]\n" + brokerJson);
                    severity = (llmResult != null && llmResult.isReal())
                            ? llmResult.severity()
                            : guessSeverity(report);
                    affectedModules = (llmResult != null && llmResult.isReal()
                            && !llmResult.affectedAreas().isEmpty())
                            ? llmResult.affectedAreas()
                            : extractAffectedFiles(report);

                } catch (Exception e) {
                    LOG.log(Level.WARNING, "[STEP2] 4-agent pipeline failed", e);
                    publishTrace(cid, "STEP_2_REASONING", "fara-hack",
                            "4-agent pipeline threw " + e.getClass().getSimpleName()
                                    + ": " + e.getMessage() + " — regex fallback");
                    severity = guessSeverity(report);
                    summary = "Auto-triage summary for: " + report.title();
                    affectedModules = extractAffectedFiles(report);
                }
            } else {
                severity = guessSeverity(report);
                summary = (llmResult != null && llmResult.isReal())
                        ? llmResult.technicalSummary()
                        : "Auto-triage summary for: " + report.title();
                affectedModules = extractAffectedFiles(report);
            }

            publishTrace(cid, "STEP_2_REASONING", "fararoni-core",
                    "Severity=" + severity + ", affectedModules=" + affectedModules);

            // Step 3 — Create ticket
            publishTrace(cid, "STEP_3_TICKET", "integration-broker",
                    "Creating ticket via " + (config.hasGitHub() ? "github" : "filesystem") + " bridge");
            final String body = "## Summary\n" + summary
                    + "\n\n## Description\n" + report.description()
                    + (report.stackTrace().isBlank() ? "" : "\n\n## Stack Trace\n```\n"
                            + report.stackTrace() + "\n```")
                    + mitigationBlock
                    + "\n\n---\n*Auto-triaged by Fara-Hack 1.0 (#AgentXHackathon)*";

            final Ticket ticket = watcher.store().create(
                    cid,
                    report.reporterEmail(),
                    report.title(),
                    body,
                    severity,
                    null,
                    affectedModules,
                    "local://ticket/" + cid,
                    sentinelVerified
            );
            publishTrace(cid, "STEP_3_TICKET", "integration-broker",
                    "Ticket created: " + ticket.ticketId());

            // Step 4 — Notify team
            publishTrace(cid, "STEP_4_NOTIFY", "integration-broker",
                    "Notifying technical team via " + (config.hasSlack() ? "slack" : "filesystem"));
            notifyTeam(ticket);
            publishTrace(cid, "STEP_4_NOTIFY", "integration-broker",
                    "Team notification dispatched");

            final String channel = "#sre-incidentes";
            LOG.info(() -> "[COMMUNICATOR] Alerta enviada al canal "
                    + channel + " — ticket=" + ticket.ticketId()
                    + " severity=" + ticket.severity()
                    + " title=" + truncateForTrace(ticket.title()));
            publishTrace(cid, "STEP_4_NOTIFY", "communicator",
                    "Alerta enviada al canal " + channel
                            + " — ticket=" + ticket.ticketId()
                            + " severity=" + ticket.severity());

            publishTrace(cid, "DONE", "controller",
                    "Pipeline complete. Ticket=" + ticket.ticketId() + ", severity=" + severity);

        } catch (Exception e) {
            LOG.log(Level.SEVERE, "pipeline failed for " + cid, e);
            publishTrace(cid, "ERROR", "controller", "Pipeline failed: " + e.getMessage());
        }
    }

    private void notifyTeam(Ticket ticket) {
        bridges.notificationBridge().ifPresentOrElse(bridge -> {
            // Defensive: skip if the bridge is not actually alive (e.g.
            // npx failed to spawn the MCP server on this host).
            if (!bridge.isAlive()) {
                LOG.warning("[STEP4] notification bridge " + bridge.instanceId()
                        + " is not alive — skipping for " + ticket.ticketId());
                return;
            }
            try {
                final ObjectNode payload = MAPPER.createObjectNode();
                payload.put("type", "team-notification");
                payload.put("ticketId", ticket.ticketId());
                payload.put("title", ticket.title());
                payload.put("severity", ticket.severity());
                payload.put("channel", config.slackChannel());
                bridge.send(payload.toString());
                LOG.info(() -> "[STEP4] team notified via " + bridge.instanceId()
                        + " for " + ticket.ticketId());
            } catch (Exception e) {
                LOG.log(Level.WARNING, "team notification failed for " + ticket.ticketId(), e);
            }
        }, () -> LOG.warning("[STEP4] no notification bridge available — skipping"));
    }

    private void notifyReporter(Ticket ticket) {
        bridges.notificationBridge().ifPresentOrElse(bridge -> {
            if (!bridge.isAlive()) {
                LOG.warning("[STEP5] notification bridge " + bridge.instanceId()
                        + " is not alive — skipping for " + ticket.ticketId());
                publishTrace(ticket.correlationId(), "STEP_5_REPORTER_NOTIFIED",
                        "integration-broker",
                        "Reporter notification skipped (no live bridge) for " + ticket.ticketId());
                return;
            }
            try {
                final ObjectNode payload = MAPPER.createObjectNode();
                payload.put("type", "reporter-notification");
                payload.put("ticketId", ticket.ticketId());
                payload.put("reporterEmail", ticket.reporterEmail());
                payload.put("title", ticket.title());
                payload.put("resolvedAt", ticket.resolvedAt() == null ? "" : ticket.resolvedAt().toString());
                bridge.send(payload.toString());
                LOG.info(() -> "[STEP5] reporter notified: " + ticket.reporterEmail()
                        + " for " + ticket.ticketId());
                publishTrace(ticket.correlationId(), "STEP_5_REPORTER_NOTIFIED",
                        "integration-broker",
                        "Reporter " + ticket.reporterEmail() + " notified for " + ticket.ticketId());
            } catch (Exception e) {
                LOG.log(Level.WARNING, "reporter notification failed for " + ticket.ticketId(), e);
            }
        }, () -> LOG.warning("[STEP5] no notification bridge available — skipping"));
    }

    // ─── validation helpers ────────────────────────────────────────────

    /** Maximum size of an attachment after base64 decode (~5 MB raw). */
    private static final int MAX_ATTACHMENT_BASE64_BYTES = 7_000_000;

    private BugReport parseAndValidate(JsonNode body) {
        if (body == null || !body.isObject()) {
            throw new IllegalArgumentException("body must be a JSON object");
        }
        final String email = textOrEmpty(body, "reporterEmail").trim();
        final String title = textOrEmpty(body, "title").trim();
        final String description = textOrEmpty(body, "description").trim();
        final String stackTrace = textOrEmpty(body, "stackTrace");

        // Optional multimodal attachment (image / log file / screenshot)
        final String attachmentBase64 = textOrEmpty(body, "attachmentBase64");
        final String attachmentMime = textOrEmpty(body, "attachmentMime").trim();
        final String attachmentName = textOrEmpty(body, "attachmentName").trim();

        if (email.isBlank() || !EMAIL.matcher(email).matches()) {
            throw new IllegalArgumentException("invalid reporterEmail");
        }
        if (title.isBlank() || title.length() > 200) {
            throw new IllegalArgumentException("title required (1-200 chars)");
        }
        if (description.isBlank() || description.length() > 8000) {
            throw new IllegalArgumentException("description required (1-8000 chars)");
        }
        if (stackTrace.length() > 16000) {
            throw new IllegalArgumentException("stackTrace too long (max 16000 chars)");
        }
        if (attachmentBase64.length() > MAX_ATTACHMENT_BASE64_BYTES) {
            throw new IllegalArgumentException("attachment too large (max ~5MB raw)");
        }

        return BugReport.fresh(
                email, title, description, stackTrace,
                attachmentBase64.isBlank() ? null : attachmentBase64,
                attachmentMime.isBlank() ? null : attachmentMime,
                attachmentName.isBlank() ? null : attachmentName
        );
    }

    private String textOrEmpty(JsonNode body, String field) {
        final JsonNode node = body.get(field);
        return node == null || node.isNull() ? "" : node.asText("");
    }

    private String guessSeverity(BugReport report) {
        final String haystack = (report.title() + " " + report.description() + " "
                + report.stackTrace()).toLowerCase();
        if (haystack.contains("crash") || haystack.contains("data loss")
                || haystack.contains("outage") || haystack.contains("payment failed")) {
            return "P0";
        }
        if (haystack.contains("error") || haystack.contains("exception")
                || haystack.contains("nullpointer") || haystack.contains("nullreference")) {
            return "P1";
        }
        if (haystack.contains("slow") || haystack.contains("timeout")) {
            return "P2";
        }
        return "P3";
    }

    /**
     * Builds the prompt that the 4-agent pipeline receives.
     * This is the entry point of the reasoning step — the agents
     * will tool-call, plan, and produce a technical
     * summary that becomes the ticket body.
     */
    private String buildCorePrompt(BugReport report, String visionEvidence) {
        final StringBuilder sb = new StringBuilder();
        sb.append("You are an SRE incident triage agent for an e-commerce platform.\n");
        sb.append("Analyze the following bug report and produce a one-paragraph technical\n");
        sb.append("summary suitable for a Jira ticket. Identify the affected subsystem,\n");
        sb.append("suggest a severity (P0/P1/P2/P3), and propose next steps.\n\n");
        sb.append("---\n");
        sb.append("Title: ").append(report.title()).append('\n');
        sb.append("Reporter: ").append(report.reporterEmail()).append('\n');
        sb.append("Description:\n").append(report.description()).append('\n');
        if (!report.stackTrace().isBlank()) {
            sb.append("\nStack trace:\n").append(report.stackTrace()).append('\n');
        }
        if (!visionEvidence.isBlank()) {
            sb.append(visionEvidence);
        }
        sb.append("\n---\n");
        sb.append("Output: a single paragraph (max 600 chars) ready to paste into a ticket body.\n");
        return sb.toString();
    }

    private static String truncateForTrace(String s) {
        if (s == null) return "";
        return s.length() <= 200 ? s : s.substring(0, 200) + "…";
    }

    /**
     * Extracts a unified diff from the mitigation-engineer agent's output.
     *
     * <p>The agent is instructed to return its proposed fix EXCLUSIVELY
     * inside a markdown {@code ```diff ... ```} code fence (or the literal
     * token {@code NO_PATCH} when no fix is possible). This helper is
     * tolerant of noise around the fence:</p>
     *
     * <ol>
     *   <li>If the output contains the {@code NO_PATCH} token → returns
     *       empty string (caller treats as "no patch, skip audit").</li>
     *   <li>If a {@code ```diff} or {@code ```patch} fence is found,
     *       returns the content inside the fence (preferred path).</li>
     *   <li>If the raw output already starts with {@code --- } (a unified
     *       diff header), returns the trimmed raw output.</li>
     *   <li>Otherwise returns empty string — the caller logs the agent
     *       prose and skips the Sentinel audit gracefully.</li>
     * </ol>
     *
     * <p>This replaces the previous JSON-based extraction which forced
     * qwen3.5 to escape newlines/tabs/quotes inside a JSON string and
     * was the primary cause of the agent falling back to prose.</p>
     */
    static String extractUnifiedDiff(String agentOutput) {
        if (agentOutput == null || agentOutput.isBlank()) return "";
        // Honor the explicit NO_PATCH kill-switch
        if (agentOutput.contains("NO_PATCH")) return "";
        // 1. Preferred: ```diff or ```patch fence
        final var fenceMatcher = DIFF_FENCE_PATTERN.matcher(agentOutput);
        if (fenceMatcher.find()) {
            return fenceMatcher.group(1).trim();
        }
        // 2. Bare diff (agent returned raw unified diff with no fence)
        final String trimmed = agentOutput.trim();
        if (trimmed.startsWith("--- ")) {
            return trimmed;
        }
        return "";
    }

    /** Compiled once. Matches a markdown ```diff or ```patch code fence. */
    private static final Pattern DIFF_FENCE_PATTERN = Pattern.compile(
            "(?s)```(?:diff|patch)?\\s*\\n(.*?)```");

    /**
     * Publishes a telemetry event to the NATS Sovereign Bus.
     * Best-effort: if bus is null or publish fails, the pipeline continues.
     * This makes the pipeline observable via NATS subscribers without
     * coupling the orchestration to the bus.
     */
    private void publishBusTelemetry(SovereignEventBus bus, String correlationId,
                                      String agentRole, String action, String message) {
        if (bus == null) return;
        try {
            final ObjectNode payload = MAPPER.createObjectNode();
            payload.put("agentId", agentRole);
            payload.put("role", agentRole);
            payload.put("action", action);
            payload.put("message", message);
            payload.put("state", action);
            payload.put("timestamp", Instant.now().toString());

            final var envelope = SovereignEnvelope.createSecure(
                agentRole, agentRole, null, "swarm.telemetry", (Object) payload
            ).withCorrelation(correlationId);

            bus.publish("swarm.telemetry", envelope);
        } catch (Exception e) {
            LOG.fine(() -> "[NATS-TELEMETRY] publish failed (non-fatal): " + e.getMessage());
        }
    }

    private static String truncateForTicket(String s) {
        if (s == null) return "";
        return s.length() <= 1000 ? s : s.substring(0, 1000) + "…";
    }

    /**
     * Helper to invoke a single agent (loaded from its YAML in the
     * AgentTemplateManager registry) via DirectAgentExecutor. Returns
     * the agent's final text output, or a JSON error envelope if the
     * agent is not loaded or the execution fails.
     *
     * <p>This is the same execution path used by the {@code /agent <id>}
     * CLI command (see {@code AgentCommand.resolveRegistryStrategy}):
     * the executor filters the global tool registry by the agent's
     * {@code allowedTools[]} list, automatically excludes
     * {@code start_mission}, and runs the LLM in a tool-calling loop
     * until the model returns a final answer or the iteration cap is
     * reached.</p>
     */
    private static String runDirectAgent(AgentTemplateManager atm,
                                          DirectAgentExecutor exec,
                                          String agentId,
                                          String userMessage) {
        if (atm == null) {
            return "{\"error\":\"AgentTemplateManager not available\"}";
        }
        final AgentTemplate template = atm.getTemplate(agentId);
        if (template == null) {
            return "{\"error\":\"agent " + agentId + " not loaded in registry\"}";
        }
        if (template.systemPrompt() == null || template.systemPrompt().isBlank()) {
            return "{\"error\":\"agent " + agentId + " has no systemPrompt\"}";
        }
        final String result = exec.execute(
                template.systemPrompt(),
                userMessage,
                template.allowedTools()
        );
        return result == null ? "" : result;
    }

    private List<String> extractAffectedFiles(BugReport report) {
        final Pattern filePattern = Pattern.compile(
                "([\\w/.-]+\\.(?:java|cs|kt|py|ts|js|yml|yaml|xml))");
        final var matcher = filePattern.matcher(report.title() + "\n" + report.stackTrace());
        final List<String> files = new java.util.ArrayList<>();
        while (matcher.find()) {
            final String f = matcher.group(1);
            if (!files.contains(f)) {
                files.add(f);
            }
        }
        return files;
    }
}
