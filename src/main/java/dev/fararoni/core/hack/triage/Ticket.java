/*
 * Copyright (c) 2026 Eber Cruz Fararoni
 *
 *  This file is part of the core infrastructure.
 *  This source code is licensed under the MIT License found in the
 *  LICENSE file in the root directory of this source tree.
 *  Any distribution or modification must retain this copyright notice.
 */
package dev.fararoni.core.hack.triage;

import java.time.Instant;
import java.util.List;

/**
 * In-memory ticket model used by the triage pipeline. Persisted to
 * the embedded ArcadeDB graph in production; held in a
 * {@code ConcurrentHashMap} during the demo.
 *
 * @param ticketId           globally unique ticket id (e.g. "FH-1")
 * @param correlationId      links back to the originating BugReport
 * @param reporterEmail      who reported it (for resolution notification)
 * @param title              ticket title
 * @param body               markdown body (summary + evidence + optional patch)
 * @param state              OPEN / IN_PROGRESS / RESOLVED
 * @param severity           P0..P3
 * @param assignedTo         suggested owner (may be null)
 * @param affectedModules    files identified by AST query (may be empty)
 * @param createdAt          creation timestamp
 * @param resolvedAt         null until state = RESOLVED
 * @param ticketUrl          URL on the upstream system (or filesystem path)
 * @param sentinelVerified   true if the attached patch passed Sentinel audit
 *
 * @author Eber Cruz
 * @version 1.0.0
 * @since 1.0.0
 */
public record Ticket(
        String ticketId,
        String correlationId,
        String reporterEmail,
        String title,
        String body,
        State state,
        String severity,
        String assignedTo,
        List<String> affectedModules,
        Instant createdAt,
        Instant resolvedAt,
        String ticketUrl,
        boolean sentinelVerified
) {

    /** Lifecycle states for a triage ticket. */
    public enum State {
        OPEN,
        IN_PROGRESS,
        RESOLVED
    }

    /** Returns a copy with updated state and resolvedAt timestamp. */
    public Ticket withState(State newState) {
        final Instant resolved = (newState == State.RESOLVED) ? Instant.now() : this.resolvedAt;
        return new Ticket(
                ticketId, correlationId, reporterEmail, title, body,
                newState, severity, assignedTo, affectedModules,
                createdAt, resolved, ticketUrl, sentinelVerified
        );
    }
}
