/*
 * Copyright (c) 2026 Eber Cruz Fararoni
 *
 *  This file is part of the core infrastructure.
 *  This source code is licensed under the MIT License found in the
 *  LICENSE file in the root directory of this source tree.
 *  Any distribution or modification must retain this copyright notice.
 */
package dev.fararoni.core.hack.llm;

import dev.fararoni.core.hack.config.HackConfig;
import dev.fararoni.core.hack.triage.BugReport;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author Eber Cruz
 * @version 1.0.0
 * @since 1.0.0
 */
@DisplayName("LlmTriageClient — multimodal triage")
class LlmTriageClientTest {

    private static HackConfig configWithLlm() {
        return new HackConfig(
                8080, "nats://localhost:4222", "tok",
                "", "ebercruz/demo", "", "#eng",
                "/tmp/sandbox", "./mock-eshop", true,
                "http://localhost:11434/v1", "llama3.2:3b", ""
        );
    }

    private static HackConfig configWithoutLlm() {
        return new HackConfig(
                8080, "nats://localhost:4222", "tok",
                "", "ebercruz/demo", "", "#eng",
                "/tmp/sandbox", "./mock-eshop", true,
                "", "llama3.2:3b", ""
        );
    }

    private static BugReport sampleReport() {
        return BugReport.fresh(
                "alice@example.com",
                "NRE on POST /items",
                "When brand field is missing the server returns 500",
                "System.NullReferenceException at CatalogController.cs:142"
        );
    }

    @Test
    @DisplayName("client is enabled when OPENAI_COMPAT_BASE_URL is set")
    void enabledWithUrl() {
        final var client = new LlmTriageClient(configWithLlm());
        assertTrue(client.isEnabled());
    }

    @Test
    @DisplayName("client is disabled when OPENAI_COMPAT_BASE_URL is blank")
    void disabledWithoutUrl() {
        final var client = new LlmTriageClient(configWithoutLlm());
        assertFalse(client.isEnabled());
    }

    @Test
    @DisplayName("returns SKIPPED result when not enabled — never throws")
    void skippedWhenDisabled() {
        final var client = new LlmTriageClient(configWithoutLlm());
        final var result = client.triage(sampleReport(), null, null);
        assertNotNull(result);
        assertFalse(result.isReal());
        assertEquals("P3", result.severity());
        assertTrue(result.technicalSummary().startsWith("LLM triage skipped"));
    }

    @Test
    @DisplayName("parses canned OpenAI-compatible response correctly")
    void parsesCannedResponse() {
        final var client = new LlmTriageClient(configWithLlm());
        final String body = """
                {
                  "id": "chatcmpl-test",
                  "object": "chat.completion",
                  "model": "llama3.2:3b",
                  "choices": [{
                    "index": 0,
                    "message": {
                      "role": "assistant",
                      "content": "{\\"severity\\":\\"P1\\",\\"technicalSummary\\":\\"NullReferenceException in CatalogController when brand field missing\\",\\"suspectedRootCause\\":\\"Missing null check on brand field\\",\\"affectedAreas\\":[\\"Catalog.API/Controllers/CatalogController.cs\\"],\\"confidence\\":0.85}"
                    }
                  }]
                }
                """;
        final var result = client.parseResponse(body, true, Instant.now());
        assertTrue(result.isReal());
        assertEquals("P1", result.severity());
        assertTrue(result.technicalSummary().contains("NullReferenceException"));
        assertEquals("Missing null check on brand field", result.suspectedRootCause());
        assertEquals(1, result.affectedAreas().size());
        assertEquals(0.85, result.confidence(), 0.001);
        assertTrue(result.multimodal());
    }

    @Test
    @DisplayName("strips ```json code fence if model adds it")
    void stripsCodeFence() {
        final var client = new LlmTriageClient(configWithLlm());
        final String body = """
                {
                  "choices": [{
                    "message": {
                      "content": "```json\\n{\\"severity\\":\\"P0\\",\\"technicalSummary\\":\\"Production outage\\",\\"suspectedRootCause\\":\\"\\",\\"affectedAreas\\":[],\\"confidence\\":0.9}\\n```"
                    }
                  }]
                }
                """;
        final var result = client.parseResponse(body, false, Instant.now());
        assertTrue(result.isReal());
        assertEquals("P0", result.severity());
        assertEquals("Production outage", result.technicalSummary());
    }

    @Test
    @DisplayName("returns SKIPPED on malformed response — never throws")
    void skippedOnMalformed() {
        final var client = new LlmTriageClient(configWithLlm());
        final var result = client.parseResponse("{ not json at all }}", false, Instant.now());
        assertFalse(result.isReal());
        assertEquals("P3", result.severity());
    }

    @Test
    @DisplayName("normalizes severity to P0/P1/P2/P3")
    void normalizesSeverity() {
        final var client = new LlmTriageClient(configWithLlm());
        // Model returns lowercase
        final String body = """
                {
                  "choices": [{
                    "message": {
                      "content": "{\\"severity\\":\\"p2 — performance regression\\",\\"technicalSummary\\":\\"Slow query\\",\\"suspectedRootCause\\":\\"\\",\\"affectedAreas\\":[],\\"confidence\\":0.6}"
                    }
                  }]
                }
                """;
        final var result = client.parseResponse(body, false, Instant.now());
        assertEquals("P2", result.severity());
    }

    @Test
    @DisplayName("clamps confidence to [0.0, 1.0]")
    void clampsConfidence() {
        final var client = new LlmTriageClient(configWithLlm());
        final String body = """
                {
                  "choices": [{
                    "message": {
                      "content": "{\\"severity\\":\\"P3\\",\\"technicalSummary\\":\\"x\\",\\"suspectedRootCause\\":\\"\\",\\"affectedAreas\\":[],\\"confidence\\":2.7}"
                    }
                  }]
                }
                """;
        final var result = client.parseResponse(body, false, Instant.now());
        assertEquals(1.0, result.confidence(), 0.001);
    }
}
