package ai.devforge.agent;

import java.util.List;
import org.jspecify.annotations.Nullable;

public record InvestigationResult(String summary, List<FindingDraft> findings, List<Hypothesis> hypotheses, List<String> unknowns, List<ToolTrace> toolTrace,
                                  LlmResponse.Usage usage, boolean incomplete, @Nullable String incompleteReason) {
    /** A complete result, for implementations that never stop early. */
    public InvestigationResult(String summary, List<FindingDraft> findings, List<Hypothesis> hypotheses, List<String> unknowns, List<ToolTrace> toolTrace, LlmResponse.Usage usage) {
        this(summary, findings, hypotheses, unknowns, toolTrace, usage, false, null);
    }
}
