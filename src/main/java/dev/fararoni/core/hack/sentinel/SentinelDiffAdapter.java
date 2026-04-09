/*
 * Copyright (c) 2026 Eber Cruz Fararoni
 *
 *  This file is part of the core infrastructure.
 *  This source code is licensed under the MIT License found in the
 *  LICENSE file in the root directory of this source tree.
 *  Any distribution or modification must retain this copyright notice.
 */
package dev.fararoni.core.hack.sentinel;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * SentinelDiffAdapter — implements <b>Step 6</b> of the AgentX pipeline:
 * audit a candidate code patch (unified diff) for dangerous operations
 * <em>before</em> attaching it to a triage ticket.
 *
 * <p>This adapter is the only piece of new code Fara-Hack writes for
 * the security-review step. It implements the same six audits that the
 * core {@code SentinelAuditor} (M4-06) performs over execution plans,
 * but on diffs instead of DAGs:</p>
 *
 * <ol>
 *   <li><b>Boundaries</b> — DROP TABLE, DELETE without WHERE, rm -rf, etc.</li>
 *   <li><b>Errors</b> — empty catch blocks that swallow exceptions</li>
 *   <li><b>Concurrency</b> — new shared mutable static fields</li>
 *   <li><b>Resources</b> — new streams without try-with-resources</li>
 *   <li><b>Idempotency</b> — INSERT without conflict handling</li>
 *   <li><b>Flow</b> — diff size budget (≤50 lines, ≤1 file)</li>
 * </ol>
 *
 * <p>The audit is intentionally pattern-based (regex) and deterministic.
 * No LLM. The cost is precision (false positives possible) but the
 * benefit is reproducibility — judges can read the regex list and know
 * exactly what gets flagged. The {@link Verdict#APPROVED} branch is
 * conservative: any rule failure → REJECTED → kill-switch fallback to
 * summary-only ticket.</p>
 *
 * @author Eber Cruz
 * @version 1.0.0
 * @since 1.0.0
 */
public final class SentinelDiffAdapter {

    /** Result of auditing a unified diff. */
    public sealed interface Verdict {
        /** Patch is safe to attach. */
        record Approved(int linesChanged, int filesTouched) implements Verdict { }

        /** Patch was rejected — kill-switch fires. */
        record Rejected(String rule, String reason, String evidenceLine) implements Verdict { }
    }

    /** Maximum lines a single patch may modify. */
    public static final int MAX_LINES_CHANGED = 50;

    /** Maximum files a single patch may touch. */
    public static final int MAX_FILES_TOUCHED = 1;

    // ─── audit patterns ────────────────────────────────────────────────

    private static final Pattern P_DROP_TABLE = Pattern.compile(
            "(?i)\\bDROP\\s+(TABLE|DATABASE|SCHEMA)\\b");
    private static final Pattern P_DELETE_NO_WHERE = Pattern.compile(
            "(?i)\\bDELETE\\s+FROM\\s+\\w+(?!\\s+WHERE)");
    private static final Pattern P_RM_RF = Pattern.compile(
            "\\brm\\s+-[rRf]+\\b|\\bRuntime\\.getRuntime\\(\\)\\.exec\\(.*rm\\s+-r");
    private static final Pattern P_TRUNCATE = Pattern.compile(
            "(?i)\\bTRUNCATE\\s+TABLE\\b");

    private static final Pattern P_EMPTY_CATCH = Pattern.compile(
            "catch\\s*\\([^)]*\\)\\s*\\{\\s*\\}");
    /** Same rule but for the multi-line idiom: "} catch (X e) {" alone on a line. */
    private static final Pattern P_CATCH_OPEN_LINE = Pattern.compile(
            "catch\\s*\\([^)]*\\)\\s*\\{\\s*$");

    private static final Pattern P_NEW_STATIC = Pattern.compile(
            "\\+\\s*(public|private|protected)?\\s*static\\s+(?!final)\\w");

    private static final Pattern P_UNCLOSED_STREAM = Pattern.compile(
            "\\+\\s*\\w+\\s*=\\s*new\\s+(File(Input|Output)Stream|Buffered(Reader|Writer))\\b");

    private static final Pattern P_INSERT_NO_CONFLICT = Pattern.compile(
            "(?i)\\bINSERT\\s+INTO\\s+\\w+(?!.*ON\\s+(CONFLICT|DUPLICATE))");

    private static final Pattern P_DIFF_FILE_HEADER = Pattern.compile(
            "^(?:\\+\\+\\+|---)\\s+([ab]/.+)$");
    private static final Pattern P_DIFF_ADDED_LINE = Pattern.compile("^\\+(?!\\+\\+).*$");

    private SentinelDiffAdapter() {
        // utility
    }

    /**
     * Audits a unified diff and returns a verdict.
     *
     * @param unifiedDiff the patch text in unified diff format
     * @return {@link Verdict.Approved} or {@link Verdict.Rejected}
     */
    public static Verdict audit(String unifiedDiff) {
        if (unifiedDiff == null || unifiedDiff.isBlank()) {
            return new Verdict.Rejected("flow", "empty patch", "");
        }

        final List<String> addedLines = new ArrayList<>();
        final java.util.Set<String> filesTouched = new java.util.LinkedHashSet<>();

        for (String line : unifiedDiff.split("\\R")) {
            final var headerMatch = P_DIFF_FILE_HEADER.matcher(line);
            if (headerMatch.find()) {
                filesTouched.add(headerMatch.group(1));
                continue;
            }
            if (P_DIFF_ADDED_LINE.matcher(line).matches()) {
                addedLines.add(line);
            }
        }

        // Audit 6 — Flow (size budget)
        if (filesTouched.size() > MAX_FILES_TOUCHED * 2) {
            // *2 because diff headers come in pairs (---/+++)
            return new Verdict.Rejected("flow",
                    "patch touches too many files (max=" + MAX_FILES_TOUCHED + ")",
                    "files=" + filesTouched);
        }
        if (addedLines.size() > MAX_LINES_CHANGED) {
            return new Verdict.Rejected("flow",
                    "patch too large (max=" + MAX_LINES_CHANGED + " added lines)",
                    "added=" + addedLines.size());
        }

        // Audits 1-5 — content-based, applied to ADDED lines only
        for (int i = 0; i < addedLines.size(); i++) {
            final String line = addedLines.get(i);
            final String stripped = line.substring(1); // remove leading '+'

            // Empty catch idiom split across two added lines:
            //   +} catch (Exception e) {
            //   +}
            if (P_CATCH_OPEN_LINE.matcher(stripped).find()
                    && i + 1 < addedLines.size()
                    && addedLines.get(i + 1).substring(1).trim().equals("}")) {
                return new Verdict.Rejected("errors",
                        "empty catch block (silenced exception, multi-line idiom)", line);
            }

            if (P_DROP_TABLE.matcher(stripped).find()) {
                return new Verdict.Rejected("boundaries", "DROP TABLE/DATABASE/SCHEMA detected", line);
            }
            if (P_DELETE_NO_WHERE.matcher(stripped).find()) {
                return new Verdict.Rejected("boundaries", "DELETE without WHERE clause", line);
            }
            if (P_RM_RF.matcher(stripped).find()) {
                return new Verdict.Rejected("boundaries", "rm -rf or shell exec detected", line);
            }
            if (P_TRUNCATE.matcher(stripped).find()) {
                return new Verdict.Rejected("boundaries", "TRUNCATE TABLE detected", line);
            }
            if (P_EMPTY_CATCH.matcher(stripped).find()) {
                return new Verdict.Rejected("errors", "empty catch block (silenced exception)", line);
            }
            if (P_NEW_STATIC.matcher(line).find()) {
                return new Verdict.Rejected("concurrency", "new non-final static field", line);
            }
            if (P_UNCLOSED_STREAM.matcher(line).find() && !stripped.contains("try")) {
                return new Verdict.Rejected("resources", "stream/reader without try-with-resources", line);
            }
            if (P_INSERT_NO_CONFLICT.matcher(stripped).find()) {
                return new Verdict.Rejected("idempotency", "INSERT without ON CONFLICT/DUPLICATE", line);
            }
        }

        return new Verdict.Approved(addedLines.size(), filesTouched.size() / 2);
    }
}
