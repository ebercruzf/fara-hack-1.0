/*
 * Copyright (c) 2026 Eber Cruz Fararoni
 *
 *  This file is part of the core infrastructure.
 *  This source code is licensed under the MIT License found in the
 *  LICENSE file in the root directory of this source tree.
 *  Any distribution or modification must retain this copyright notice.
 */
package dev.fararoni.core.hack.sentinel;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author Eber Cruz
 * @version 1.0.0
 * @since 1.0.0
 */
@DisplayName("SentinelDiffAdapter — Step 6 audits")
class SentinelDiffAdapterTest {

    @Test
    @DisplayName("safe one-line patch is APPROVED")
    void safePatchApproved() {
        final String diff = """
                --- a/src/Foo.java
                +++ b/src/Foo.java
                @@ -10,3 +10,4 @@
                 public void doSomething() {
                +    log.info("entering doSomething");
                     return;
                 }
                """;
        final var v = SentinelDiffAdapter.audit(diff);
        assertInstanceOf(SentinelDiffAdapter.Verdict.Approved.class, v);
    }

    @Test
    @DisplayName("DROP TABLE → REJECTED (boundaries)")
    void dropTableRejected() {
        final String diff = """
                --- a/sql/migration.sql
                +++ b/sql/migration.sql
                @@ -1,1 +1,2 @@
                 CREATE TABLE users(id INT);
                +DROP TABLE legacy_users;
                """;
        final var v = SentinelDiffAdapter.audit(diff);
        final var rejected = assertInstanceOf(SentinelDiffAdapter.Verdict.Rejected.class, v);
        assertTrue(rejected.rule().equals("boundaries"));
    }

    @Test
    @DisplayName("DELETE without WHERE → REJECTED (boundaries)")
    void deleteNoWhereRejected() {
        final String diff = """
                --- a/cleanup.sql
                +++ b/cleanup.sql
                @@ -1,1 +1,2 @@
                 -- cleanup script
                +DELETE FROM orders;
                """;
        final var v = SentinelDiffAdapter.audit(diff);
        final var rejected = assertInstanceOf(SentinelDiffAdapter.Verdict.Rejected.class, v);
        assertTrue(rejected.rule().equals("boundaries"));
    }

    @Test
    @DisplayName("rm -rf → REJECTED (boundaries)")
    void rmRfRejected() {
        final String diff = """
                --- a/scripts/clean.sh
                +++ b/scripts/clean.sh
                @@ -1,1 +1,2 @@
                 #!/bin/bash
                +rm -rf /var/lib/data
                """;
        final var v = SentinelDiffAdapter.audit(diff);
        final var rejected = assertInstanceOf(SentinelDiffAdapter.Verdict.Rejected.class, v);
        assertTrue(rejected.rule().equals("boundaries"));
    }

    @Test
    @DisplayName("empty catch block → REJECTED (errors)")
    void emptyCatchRejected() {
        final String diff = """
                --- a/Foo.java
                +++ b/Foo.java
                @@ -10,3 +10,5 @@
                 try {
                     doIt();
                +} catch (Exception e) {
                +}
                 return;
                """;
        final var v = SentinelDiffAdapter.audit(diff);
        final var rejected = assertInstanceOf(SentinelDiffAdapter.Verdict.Rejected.class, v);
        assertTrue(rejected.rule().equals("errors"));
    }

    @Test
    @DisplayName("INSERT without conflict → REJECTED (idempotency)")
    void insertNoConflictRejected() {
        final String diff = """
                --- a/db.sql
                +++ b/db.sql
                @@ -1,1 +1,2 @@
                 -- audit
                +INSERT INTO audit_log VALUES (1, 'event');
                """;
        final var v = SentinelDiffAdapter.audit(diff);
        final var rejected = assertInstanceOf(SentinelDiffAdapter.Verdict.Rejected.class, v);
        assertTrue(rejected.rule().equals("idempotency"));
    }

    @Test
    @DisplayName("oversized patch → REJECTED (flow)")
    void oversizedRejected() {
        final StringBuilder sb = new StringBuilder("--- a/x.java\n+++ b/x.java\n@@ -1,1 +1,100 @@\n");
        for (int i = 0; i < 60; i++) {
            sb.append("+    log.info(\"line ").append(i).append("\");\n");
        }
        final var v = SentinelDiffAdapter.audit(sb.toString());
        final var rejected = assertInstanceOf(SentinelDiffAdapter.Verdict.Rejected.class, v);
        assertTrue(rejected.rule().equals("flow"));
    }

    @Test
    @DisplayName("empty patch → REJECTED (flow)")
    void emptyRejected() {
        final var v = SentinelDiffAdapter.audit("");
        assertInstanceOf(SentinelDiffAdapter.Verdict.Rejected.class, v);
    }
}
