/*
 * Copyright (c) 2026 Eber Cruz Fararoni
 *
 *  This file is part of the core infrastructure.
 *  This source code is licensed under the MIT License found in the
 *  LICENSE file in the root directory of this source tree.
 *  Any distribution or modification must retain this copyright notice.
 */
package dev.fararoni.core.hack.llm;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Immutable result of a multimodal triage call to an OpenAI-compatible LLM.
 *
 * @param severity         normalized severity hint extracted from the LLM's reasoning
 *                         (P0/P1/P2/P3) — never null
 * @param technicalSummary one-paragraph technical summary of the bug, written in
 *                         engineering tone
 * @param suspectedRootCause LLM's best-effort root-cause hypothesis (may be empty)
 * @param affectedAreas    list of subsystems / files / modules the LLM identified
 *                         as likely affected (may be empty)
 * @param confidence       0.0..1.0 — the LLM's stated confidence in its summary
 * @param model            the model id that produced this result (echoed for audit)
 * @param latency          end-to-end call latency
 * @param multimodal       true if the call sent at least one non-text modality
 *                         (image, log file, etc.) — proves the multimodal path
 * @param requestedAt      timestamp when the call was issued
 *
 * @author Eber Cruz
 * @version 1.0.0
 * @since 1.0.0
 */
public record LlmTriageResult(
        String severity,
        String technicalSummary,
        String suspectedRootCause,
        List<String> affectedAreas,
        double confidence,
        String model,
        Duration latency,
        boolean multimodal,
        Instant requestedAt
) {

    /** Empty/skipped result returned when the LLM is not configured or fails. */
    public static LlmTriageResult skipped(String reason) {
        return new LlmTriageResult(
                "P3",
                "LLM triage skipped: " + reason,
                "",
                List.of(),
                0.0,
                "none",
                Duration.ZERO,
                false,
                Instant.now()
        );
    }

    public boolean isReal() {
        return !"none".equals(model) && !technicalSummary.startsWith("LLM triage skipped");
    }
}
