package ai.devforge.agent.internal.real;

import ai.devforge.agent.Confidence;
import ai.devforge.agent.EvidenceDraft;
import ai.devforge.agent.FindingDraft;
import ai.devforge.agent.FindingLabel;
import ai.devforge.agent.Hypothesis;
import ai.devforge.agent.RepoTarget;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Turns the model's submit_findings arguments into drafts. Anything malformed is reported back to the model to fix; anything that points at a
 * repository outside the run is dropped and recorded (the workflow would refuse it anyway). The model's labels are a request, not a fact.
 */
final class FindingsParser {
    record Parsed(boolean ok, String error, String summary, List<FindingDraft> findings, List<Hypothesis> hypotheses, List<String> unknowns) {}

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final int MAX_FINDINGS = 30;
    private static final int MAX_EVIDENCE = 6;
    private static final int MAX_TEXT = 1200;

    private FindingsParser() {}

    static Parsed parse(String argumentsJson, List<RepoTarget> repos) {
        JsonNode root;
        try {
            root = JSON.readTree(argumentsJson == null ? "{}" : argumentsJson);
        } catch (RuntimeException e) {
            return fail("The arguments were not valid JSON.");
        }
        if (!root.path("findings").isArray()) return fail("`findings` must be an array.");
        if (!root.path("summary").isString() || root.path("summary").asString().isBlank()) return fail("`summary` is required.");

        List<String> unknowns = new ArrayList<>();
        root.path("unknowns").forEach(u -> unknowns.add(clip(u.asString(""), MAX_TEXT)));
        List<FindingDraft> findings = new ArrayList<>();
        for (JsonNode f : root.path("findings")) {
            if (findings.size() >= MAX_FINDINGS) {
                unknowns.add("More than " + MAX_FINDINGS + " findings were submitted; the rest were ignored.");
                break;
            }
            String text = clip(f.path("text").asString(""), MAX_TEXT);
            if (text.isBlank()) continue;
            List<EvidenceDraft> evidence = new ArrayList<>();
            int count = 0;
            for (JsonNode e : f.path("evidence")) {
                if (++count > MAX_EVIDENCE) break;
                RepoTarget repo = repos.stream().filter(r -> r.name().equals(e.path("repo").asString(""))).findFirst().orElse(null);
                int start = e.path("startLine").asInt(0), end = e.path("endLine").asInt(0);
                if (repo == null) {
                    unknowns.add("A citation named a repository that is not part of this run ('" + clip(e.path("repo").asString(""), 60) + "') and was discarded.");
                    continue;
                }
                if (start < 1 || end < start || e.path("path").asString("").isBlank()) {
                    unknowns.add("A citation in " + repo.name() + " had an invalid path or line range and was discarded.");
                    continue;
                }
                evidence.add(new EvidenceDraft(repo.repositoryId(), e.path("path").asString(), start, end));
            }
            findings.add(new FindingDraft(enumOr(FindingLabel.class, f.path("label").asString(""), FindingLabel.HYPOTHESIS), text,
                enumOr(Confidence.class, f.path("confidence").asString(""), Confidence.LOW), evidence));
        }
        List<Hypothesis> hypotheses = new ArrayList<>();
        for (JsonNode h : root.path("hypotheses")) {
            String text = clip(h.path("text").asString(""), MAX_TEXT);
            if (text.isBlank()) continue;
            hypotheses.add(new Hypothesis(text, enumOr(Confidence.class, h.path("confidence").asString(""), Confidence.LOW), strings(h.path("supporting")), strings(h.path("contradicting"))));
        }
        return new Parsed(true, null, clip(root.path("summary").asString(), MAX_TEXT), findings, hypotheses, unknowns);
    }

    private static Parsed fail(String why) { return new Parsed(false, why, null, List.of(), List.of(), List.of()); }

    private static List<String> strings(JsonNode n) {
        List<String> out = new ArrayList<>();
        n.forEach(x -> out.add(clip(x.asString(""), MAX_TEXT)));
        return out;
    }

    private static <E extends Enum<E>> E enumOr(Class<E> type, String value, E fallback) {
        try {
            return Enum.valueOf(type, value.toUpperCase());
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    private static String clip(String s, int n) { return s.length() <= n ? s : s.substring(0, n) + "…"; }
}
