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
import java.util.UUID;

/**
 * Immutable bug report submitted by a reporter through the input UI.
 *
 * <h2>Multimodal payload</h2>
 * <p>The {@code attachmentBase64} field carries an optional second
 * modality (image, log file, screenshot) for the multimodal LLM
 * triage. This satisfies the AgentX hackathon Minimum Requirement
 * #1: <em>"Accept at least text + one other modality (e.g.,
 * image/log file/video) and use a multimodal LLM."</em></p>
 *
 * @param correlationId    fresh UUID generated at submission
 * @param reporterEmail    email used to notify the reporter when resolved
 * @param title            short summary (max 200 chars, validated upstream)
 * @param description      free-form description (max 8000 chars)
 * @param stackTrace       optional stack trace (max 16000 chars)
 * @param attachmentBase64 optional base64-encoded attachment (image/log)
 * @param attachmentMime   MIME type of the attachment (e.g. "image/png")
 * @param attachmentName   original filename (for the audit trail)
 * @param submittedAt      server-side timestamp at submission
 *
 * @author Eber Cruz
 * @version 1.0.0
 * @since 1.0.0
 */
public record BugReport(
        String correlationId,
        String reporterEmail,
        String title,
        String description,
        String stackTrace,
        String attachmentBase64,
        String attachmentMime,
        String attachmentName,
        Instant submittedAt
) {

    public static BugReport fresh(String reporterEmail,
                                   String title,
                                   String description,
                                   String stackTrace) {
        return fresh(reporterEmail, title, description, stackTrace, null, null, null);
    }

    public static BugReport fresh(String reporterEmail,
                                   String title,
                                   String description,
                                   String stackTrace,
                                   String attachmentBase64,
                                   String attachmentMime,
                                   String attachmentName) {
        return new BugReport(
                UUID.randomUUID().toString(),
                reporterEmail,
                title,
                description,
                stackTrace == null ? "" : stackTrace,
                attachmentBase64,
                attachmentMime,
                attachmentName,
                Instant.now()
        );
    }

    /** True if the report carries a non-text modality (image, log, etc). */
    public boolean hasAttachment() {
        return attachmentBase64 != null && !attachmentBase64.isBlank();
    }
}
