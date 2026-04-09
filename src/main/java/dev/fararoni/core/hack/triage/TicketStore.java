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
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * Thread-safe in-memory ticket store for the demo. Backed by a
 * {@link ConcurrentHashMap}; production deployments would swap this for
 * an ArcadeDB-backed implementation that persists ticket nodes in the
 * graph (already supported by {@code fararoni-core/.../persistence/arcadedb}).
 *
 * <p>Kept deliberately simple for the hackathon: no JPA, no Spring Data,
 * no abstraction layers. Just a {@code Map} and a counter.</p>
 *
 * @author Eber Cruz
 * @version 1.0.0
 * @since 1.0.0
 */
public final class TicketStore {

    private final ConcurrentHashMap<String, Ticket> byId = new ConcurrentHashMap<>();
    private final AtomicLong sequence = new AtomicLong(0);

    /** Creates a new ticket with auto-generated id (FH-1, FH-2, ...). */
    public Ticket create(String correlationId,
                          String reporterEmail,
                          String title,
                          String body,
                          String severity,
                          String assignedTo,
                          List<String> affectedModules,
                          String ticketUrl,
                          boolean sentinelVerified) {
        final String id = "FH-" + sequence.incrementAndGet();
        final Ticket ticket = new Ticket(
                id, correlationId, reporterEmail, title, body,
                Ticket.State.OPEN, severity, assignedTo,
                affectedModules == null ? List.of() : List.copyOf(affectedModules),
                Instant.now(), null, ticketUrl, sentinelVerified
        );
        byId.put(id, ticket);
        return ticket;
    }

    public Optional<Ticket> find(String ticketId) {
        return Optional.ofNullable(byId.get(ticketId));
    }

    /** Updates state. Returns the new ticket, or empty if not found. */
    public Optional<Ticket> updateState(String ticketId, Ticket.State newState) {
        return Optional.ofNullable(byId.computeIfPresent(ticketId,
                (k, current) -> current.withState(newState)));
    }

    /** Returns all tickets currently in RESOLVED state. */
    public List<Ticket> resolved() {
        return byId.values().stream()
                .filter(t -> t.state() == Ticket.State.RESOLVED)
                .sorted((a, b) -> a.createdAt().compareTo(b.createdAt()))
                .collect(Collectors.toUnmodifiableList());
    }

    /** Returns all tickets, ordered by creation. */
    public List<Ticket> all() {
        return byId.values().stream()
                .sorted((a, b) -> a.createdAt().compareTo(b.createdAt()))
                .collect(Collectors.toUnmodifiableList());
    }

    public int count() {
        return byId.size();
    }
}
