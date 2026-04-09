/*
 * Copyright (c) 2026 Eber Cruz Fararoni
 *
 *  This file is part of the core infrastructure.
 *  This source code is licensed under the MIT License found in the
 *  LICENSE file in the root directory of this source tree.
 *  Any distribution or modification must retain this copyright notice.
 */
package dev.fararoni.core.hack.triage;

import dev.fararoni.core.hack.config.HackConfig;
import dev.fararoni.core.hack.mcp.McpBridgeManager;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * TriageStatusWatcher — implements <b>Step 5</b> of the AgentX
 * pipeline: notify the original reporter when a ticket becomes
 * resolved.
 *
 * <p>This is a lightweight Virtual Thread that polls the in-memory
 * {@link TicketStore} every {@code POLL_INTERVAL} (and, when integrated
 * with a real ticketing MCP bridge, also queries
 * {@code mcp.github.list_issues?state=closed} for upstream
 * resolutions). For each newly RESOLVED ticket, it invokes the
 * registered {@link #onResolved} consumer, which is wired by
 * {@code BugReportController} to send the reporter notification via the
 * {@link McpBridgeManager#notificationBridge() notificationBridge}.</p>
 *
 * <h2>Why a watcher and not a webhook</h2>
 * <ul>
 *   <li>Webhooks require a public URL — out of scope for the hackathon
 *       Docker compose</li>
 *   <li>Polling is deterministic, easy to test, and survives NATS
 *       disconnects</li>
 *   <li>30-second cadence is well within the demo budget</li>
 * </ul>
 *
 * <h2>Anti-duplication</h2>
 * <p>The watcher tracks a set of {@code notifiedTicketIds} so the same
 * RESOLVED transition is never reported twice. The set is bounded by
 * the number of resolved tickets in this process — for the demo this
 * is fine. A production deployment would persist the cursor in
 * ArcadeDB.</p>
 *
 * @author Eber Cruz
 * @version 1.0.0
 * @since 1.0.0
 */
public final class TriageStatusWatcher {

    private static final Logger LOG = Logger.getLogger(TriageStatusWatcher.class.getName());
    private static final Duration POLL_INTERVAL = Duration.ofSeconds(30);

    private final HackConfig config;
    @SuppressWarnings("unused") // reserved for future MCP github polling integration
    private final McpBridgeManager bridges;
    private final TicketStore store;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicLong pollCount = new AtomicLong(0);
    private final AtomicLong notifications = new AtomicLong(0);
    private final Set<String> notifiedTicketIds = new HashSet<>();

    private volatile Instant lastPoll = Instant.EPOCH;
    private volatile Consumer<Ticket> onResolved = ticket -> { };
    private Thread loopThread;

    public TriageStatusWatcher(HackConfig config, McpBridgeManager bridges) {
        this(config, bridges, new TicketStore());
    }

    public TriageStatusWatcher(HackConfig config, McpBridgeManager bridges, TicketStore store) {
        this.config = config;
        this.bridges = bridges;
        this.store = store;
    }

    /** The controller registers a callback to send the reporter notification. */
    public void onTicketResolved(Consumer<Ticket> consumer) {
        if (consumer != null) {
            this.onResolved = consumer;
        }
    }

    public TicketStore store() {
        return store;
    }

    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        loopThread = Thread.ofVirtual()
                .name("triage-status-watcher")
                .start(this::loop);
        LOG.info(() -> "[WATCHER] started — interval=" + POLL_INTERVAL);
    }

    public void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        if (loopThread != null) {
            loopThread.interrupt();
        }
        LOG.info(() -> "[WATCHER] stopped — polls=" + pollCount.get()
                + ", notifications=" + notifications.get());
    }

    public boolean isRunning() {
        return running.get();
    }

    public long pollCount() {
        return pollCount.get();
    }

    public long notificationCount() {
        return notifications.get();
    }

    public Instant lastPoll() {
        return lastPoll;
    }

    // ─── internals ─────────────────────────────────────────────────────

    private void loop() {
        while (running.get()) {
            try {
                tick();
                Thread.sleep(POLL_INTERVAL.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                LOG.log(Level.WARNING, "[WATCHER] tick failed (continuing)", e);
            }
        }
    }

    /** One poll cycle — visible for unit testing. */
    void tick() {
        pollCount.incrementAndGet();
        lastPoll = Instant.now();

        // 1. Local resolved tickets (in-memory store)
        final List<Ticket> resolved = store.resolved();
        for (Ticket ticket : resolved) {
            if (notifiedTicketIds.add(ticket.ticketId())) {
                fireResolved(ticket);
            }
        }

        // 2. TODO upstream poll: mcp.github.list_issues(state=closed)
        //    Parse JSON-RPC response, build Ticket synthetics, dedupe by id.
        //    Out of scope for v1.0.0 — demo uses local TicketStore.
    }

    private void fireResolved(Ticket ticket) {
        try {
            LOG.info(() -> "[WATCHER] resolved → notifying reporter "
                    + ticket.reporterEmail() + " for " + ticket.ticketId());
            onResolved.accept(ticket);
            notifications.incrementAndGet();
        } catch (Exception e) {
            LOG.log(Level.WARNING, "onResolved callback failed for " + ticket.ticketId(), e);
        }
    }
}
