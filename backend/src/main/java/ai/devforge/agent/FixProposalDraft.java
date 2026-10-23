package ai.devforge.agent;

import java.util.List;

/** @param filesToChange entries like {@code repoName:path} so a multi-repo change set is explicit */
public record FixProposalDraft(String summary, String recommended, List<String> alternatives, List<String> filesToChange, List<String> commands, List<String> risks,
                               List<String> testPlan, List<String> outOfScope, boolean simulated) {}
