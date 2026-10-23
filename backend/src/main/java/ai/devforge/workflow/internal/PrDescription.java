package ai.devforge.workflow.internal;

import ai.devforge.agent.FixProposalDraft;
import ai.devforge.workflow.RunViews.ChangeView;
import ai.devforge.workflow.RunViews.FindingView;
import ai.devforge.workflow.RunViews.ValidationView;
import java.util.List;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;

/**
 * The pull-request text, built from stored facts only (no model call): what the problem was and the evidence for it, what changed, and what was
 * and was not checked. A check that did not run is stated as not run. Ticket and finding text is untrusted data and is quoted, not interpreted.
 */
final class PrDescription {
    private PrDescription() {}

    static String build(String runId, @Nullable String ticketKey, String subject, @Nullable FixProposalDraft proposal, List<FindingView> findings, List<ChangeView> changes, List<ValidationView> validations) {
        StringBuilder sb = new StringBuilder("# ").append(subject).append("\n\n");
        if (ticketKey != null) sb.append("**Ticket:** ").append(ticketKey).append("\n\n");
        sb.append("## Problem and cause\n");
        if (proposal != null) sb.append(proposal.summary()).append("\n\n");
        var evidenced = findings.stream().filter(f -> f.label().equals("VERIFIED") || f.label().equals("SUPPORTED")).limit(6).toList();
        for (FindingView f : evidenced) {
            sb.append("- **").append(f.label().toLowerCase()).append("**: ").append(oneLine(f.text()));
            String cites = f.evidence().stream().filter(e -> e.verified()).limit(3).map(e -> "`" + e.repoName() + ":" + e.path() + ":" + e.startLine() + "-" + e.endLine() + "` @ `" + e.sha().substring(0, Math.min(7, e.sha().length())) + "`")
                .collect(Collectors.joining(", "));
            if (!cites.isEmpty()) sb.append(" (").append(cites).append(")");
            sb.append('\n');
        }
        if (evidenced.isEmpty()) sb.append("_No finding in this run was backed by verified evidence; treat the cause as unconfirmed._\n");
        sb.append("\n## Change\n");
        if (proposal != null) sb.append(proposal.recommended()).append("\n\n");
        for (ChangeView c : changes) sb.append("- **").append(c.repoName()).append("**: ").append(String.join(", ", c.files().stream().map(f -> "`" + f + "`").toList())).append('\n');
        sb.append("\n## How it was checked\n");
        if (validations.isEmpty()) sb.append("Nothing was run for this change.\n");
        else {
            sb.append("| Repository | Result | Detail |\n|---|---|---|\n");
            for (ValidationView v : validations) {
                String detail = oneLine(v.summary().split("\n--- output", 2)[0]);
                sb.append("| ").append(v.repoName()).append(" | ").append(label(v.status())).append(" | ").append(v.command() == null || v.command().isBlank() ? "" : "`" + v.command() + "` — ").append(detail).append(" |\n");
            }
        }
        if (proposal != null && !proposal.risks().isEmpty()) sb.append("\n## Review notes\n").append(proposal.risks().stream().map(r -> "- " + oneLine(r)).collect(Collectors.joining("\n"))).append('\n');
        if (proposal != null && !proposal.outOfScope().isEmpty()) sb.append("\n**Out of scope:** ").append(String.join("; ", proposal.outOfScope().stream().map(PrDescription::oneLine).toList())).append('\n');
        sb.append("\n---\nPrepared with DevForge AI (run ").append(runId).append("). The proposal was approved by a person before any change was made; please review the diff before merging.\n");
        return sb.toString();
    }

    private static String label(String status) {
        return switch (status) {
            case "PASSED" -> "passed";
            case "FAILED" -> "**failed**";
            case "NOT_RUN" -> "**not run**";
            default -> "not executed (" + status.toLowerCase() + ")";
        };
    }

    static String oneLine(String s) {
        String t = s.replaceAll("\\s+", " ").strip().replace("|", "\\|");
        return t.length() <= 400 ? t : t.substring(0, 400) + "…";
    }
}
