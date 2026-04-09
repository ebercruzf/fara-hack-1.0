/*
 * Copyright (c) 2026 Eber Cruz Fararoni
 *
 *  This file is part of the core infrastructure.
 *  This source code is licensed under the MIT License found in the
 *  LICENSE file in the root directory of this source tree.
 *  Any distribution or modification must retain this copyright notice.
 */
package dev.fararoni.core.hack.mcp;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Minimal MCP bridge — in-house, MIT, no enterprise dependency.
 *
 * <p>Spawns an MCP server as a child process (e.g.
 * {@code npx -y @modelcontextprotocol/server-github}) and bridges its
 * stdin/stdout to the in-process consumers via simple write/read APIs.
 * For the hackathon submission this is intentionally simpler than the
 * enterprise sidecar — no NATS subjects, no SATI telemetry, no isolated
 * sentinel pattern. Just spawn, watch, restart on death.</p>
 *
 * <h2>Why in-house</h2>
 * <ul>
 *   <li>The enterprise {@code McpBridgeSidecar} lives in
 *       {@code dev.fararoni.enterprise} and is licensed commercially.</li>
 *   <li>{@code fara-hack-1.0} is MIT and depends only on
 *       {@code fararoni-core} (Apache 2.0). Importing the enterprise
 *       sidecar would contaminate the license boundary.</li>
 *   <li>~150 LOC of pragmatic stdio bridging is enough for a hackathon
 *       demo and easy for judges to audit.</li>
 * </ul>
 *
 * <h2>Lifecycle</h2>
 * <ol>
 *   <li>{@link #start()} spawns the child process and two virtual threads
 *       (stdout reader, stderr reader)</li>
 *   <li>{@link #send(String)} writes one line to the child's stdin</li>
 *   <li>The most recent stdout line is buffered and accessible via
 *       {@link #pollResponse()} (best-effort, line-oriented)</li>
 *   <li>A watchdog virtual thread restarts the child if it dies</li>
 *   <li>{@link #stop()} terminates the child gracefully (SIGTERM, then
 *       SIGKILL after 5 seconds)</li>
 * </ol>
 *
 * @author Eber Cruz
 * @version 1.0.0
 * @since 1.0.0
 */
public final class MinimalMcpBridge {

    private static final Logger LOG = Logger.getLogger(MinimalMcpBridge.class.getName());
    private static final long WATCHDOG_INTERVAL_MS = 30_000L;
    private static final long SHUTDOWN_GRACE_MS = 5_000L;

    private final String instanceId;
    private final String[] command;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicLong invocations = new AtomicLong(0);
    private final AtomicLong restarts = new AtomicLong(0);

    private volatile Process process;
    private volatile BufferedWriter stdin;
    private volatile BufferedReader stdout;
    private volatile String lastResponse;

    private Thread stdoutReader;
    private Thread stderrReader;
    private Thread watchdog;

    /**
     * @param instanceId logical name (used in logs and metrics)
     * @param command    the command line to spawn (e.g.
     *                   {@code "npx", "-y", "@modelcontextprotocol/server-github"})
     */
    public MinimalMcpBridge(String instanceId, String[] command) {
        if (instanceId == null || instanceId.isBlank()) {
            throw new IllegalArgumentException("instanceId required");
        }
        if (command == null || command.length == 0) {
            throw new IllegalArgumentException("command required");
        }
        this.instanceId = instanceId;
        this.command = command.clone();
    }

    /** Spawn the child process and start bridging threads. */
    public synchronized void start() throws Exception {
        if (running.get()) {
            return;
        }
        spawnProcess();
        running.set(true);
        startWatchdog();
        LOG.info(() -> "[MCP-BRIDGE/" + instanceId + "] started — pid="
                + (process != null ? process.pid() : -1));
    }

    /** Send one line to the child's stdin. */
    public synchronized void send(String line) throws Exception {
        if (!running.get() || stdin == null) {
            throw new IllegalStateException("bridge not running: " + instanceId);
        }
        stdin.write(line);
        stdin.write('\n');
        stdin.flush();
        invocations.incrementAndGet();
    }

    /** Returns the last received stdout line, or {@code null} if none yet. */
    public String pollResponse() {
        return lastResponse;
    }

    public boolean isAlive() {
        return process != null && process.isAlive();
    }

    public long invocationCount() {
        return invocations.get();
    }

    public long restartCount() {
        return restarts.get();
    }

    public String instanceId() {
        return instanceId;
    }

    /** Terminate the child gracefully (SIGTERM then SIGKILL after grace). */
    public synchronized void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        if (process != null) {
            process.destroy();
            try {
                if (!process.waitFor(SHUTDOWN_GRACE_MS, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                    process.destroyForcibly();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
            }
        }
        if (stdoutReader != null) stdoutReader.interrupt();
        if (stderrReader != null) stderrReader.interrupt();
        if (watchdog != null)     watchdog.interrupt();
        LOG.info(() -> "[MCP-BRIDGE/" + instanceId + "] stopped");
    }

    // ─── internals ─────────────────────────────────────────────────────

    private void spawnProcess() throws Exception {
        final ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(false);
        process = pb.start();
        stdin = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
        stdout = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));

        stdoutReader = Thread.ofVirtual()
                .name("mcp-bridge-" + instanceId + "-stdout")
                .start(this::readStdoutLoop);

        stderrReader = Thread.ofVirtual()
                .name("mcp-bridge-" + instanceId + "-stderr")
                .start(this::readStderrLoop);
    }

    private void readStdoutLoop() {
        try {
            String line;
            while ((line = stdout.readLine()) != null) {
                final String captured = line;
                lastResponse = captured;
                LOG.fine(() -> "[MCP-BRIDGE/" + instanceId + "] <- " + captured);
            }
        } catch (Exception e) {
            if (running.get()) {
                LOG.log(Level.FINE, "stdout loop ended for " + instanceId, e);
            }
        }
    }

    private void readStderrLoop() {
        try (BufferedReader err = new BufferedReader(
                new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = err.readLine()) != null) {
                LOG.warning("[MCP-BRIDGE/" + instanceId + "] STDERR: " + line);
            }
        } catch (Exception ignored) { }
    }

    private void startWatchdog() {
        watchdog = Thread.ofVirtual()
                .name("mcp-bridge-" + instanceId + "-watchdog")
                .start(() -> {
                    while (running.get()) {
                        try {
                            Thread.sleep(WATCHDOG_INTERVAL_MS);
                            if (running.get() && (process == null || !process.isAlive())) {
                                restarts.incrementAndGet();
                                LOG.warning("[MCP-BRIDGE/" + instanceId
                                        + "] child died, restarting (count=" + restarts.get() + ")");
                                try {
                                    spawnProcess();
                                } catch (Exception e) {
                                    LOG.log(Level.SEVERE, "restart failed for " + instanceId, e);
                                }
                            }
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                    }
                });
    }
}
