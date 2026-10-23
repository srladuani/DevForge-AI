package ai.devforge.agent.internal.real;

import ai.devforge.agent.FixProposalDraft;
import ai.devforge.agent.RepoTarget;
import ai.devforge.agent.internal.real.ToolLoop.Parsed;
import ai.devforge.execution.ProtectedPaths;
import ai.devforge.platform.DomainException;
import ai.devforge.repository.RepoReader;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Turns the model's submit_proposal arguments into a proposal, rejecting anything the platform cannot stand behind (unknown repos, invented paths, secrets). */
final class ProposalParser {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final int MAX_FILES = 20;
    static final String NEW_SUFFIX = " (new)";

    private ProposalParser() {}

    static Parsed<FixProposalDraft> parse(String argumentsJson, List<RepoTarget> repos, RepoReader reader) {
        JsonNode a;
        try {
            a = JSON.readTree(argumentsJson == null ? "{}" : argumentsJson);
        } catch (RuntimeException e) {
            return Parsed.bad("The arguments were not valid JSON.");
        }
        String summary = text(a, "summary"), recommended = text(a, "recommended");
        if (summary.isBlank() || recommended.isBlank()) return Parsed.bad("`summary` and `recommended` are required.");
        JsonNode files = a.path("files");
        if (!files.isArray() || files.isEmpty() || files.size() > MAX_FILES) return Parsed.bad("`files` must list between 1 and " + MAX_FILES + " files to change.");
        List<String> entries = new ArrayList<>();
        List<String> risks = strings(a, "risks");
        for (JsonNode f : files) {
            String repoName = f.path("repo").asString(""), path = f.path("path").asString("");
            boolean isNew = f.path("isNew").asBoolean(false);
            RepoTarget repo = repos.stream().filter(r -> r.name().equals(repoName)).findFirst().orElse(null);
            if (repo == null) return Parsed.bad("Unknown repository '" + clip(repoName) + "'. Use one of: " + String.join(", ", repos.stream().map(RepoTarget::name).toList()) + ".");
            if (path.isBlank() || path.startsWith("/") || path.contains("..") || path.contains("\\")) return Parsed.bad("Invalid path '" + clip(path) + "'.");
            if (ProtectedPaths.never(path)) return Parsed.bad(path + " is restricted (secrets and credentials can never be changed). Remove it.");
            if (ProtectedPaths.needsApproval(path) && risks.stream().noneMatch(r -> r.contains(path))) {
                return Parsed.bad(path + " is build or CI configuration. Explain in `risks` (mentioning the path) why it must change, or remove it.");
            }
            boolean exists = exists(reader, repo, path);
            if (isNew && exists) return Parsed.bad(repoName + ":" + path + " already exists, so it is not new. Mark isNew=false.");
            if (!isNew && !exists) return Parsed.bad(repoName + ":" + path + " does not exist at the pinned commit. Check the path, or mark it isNew=true if you mean to create it.");
            String entry = repoName + ":" + path + (isNew ? NEW_SUFFIX : "");
            if (entries.contains(entry)) return Parsed.bad("Duplicate file " + entry + ".");
            entries.add(entry);
        }
        return Parsed.ok(new FixProposalDraft(summary, recommended, strings(a, "alternatives"), List.copyOf(entries), strings(a, "commands"), risks, strings(a, "testPlan"), strings(a, "outOfScope"), false));
    }

    private static boolean exists(RepoReader reader, RepoTarget repo, String path) {
        try {
            reader.read(repo.repositoryId(), repo.sha(), path, 1, 1);
            return true;
        } catch (DomainException e) {
            return false;
        }
    }

    private static String text(JsonNode a, String f) { return a.path(f).asString("").strip(); }

    private static List<String> strings(JsonNode a, String f) {
        List<String> out = new ArrayList<>();
        a.path(f).forEach(n -> { String s = n.asString("").strip(); if (!s.isEmpty() && out.size() < 20) out.add(s.length() > 500 ? s.substring(0, 500) : s); });
        return List.copyOf(out);
    }

    private static String clip(String s) { return s.length() <= 80 ? s : s.substring(0, 80) + "…"; }
}
