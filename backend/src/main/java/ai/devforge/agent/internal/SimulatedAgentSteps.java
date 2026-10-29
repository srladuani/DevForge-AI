package ai.devforge.agent.internal;

import ai.devforge.agent.AgentSteps;
import ai.devforge.agent.ChangeDraft;
import ai.devforge.agent.Confidence;
import ai.devforge.agent.EvidenceDraft;
import ai.devforge.agent.FindingDraft;
import ai.devforge.agent.FindingLabel;
import ai.devforge.agent.FixProposalDraft;
import ai.devforge.agent.Hypothesis;
import ai.devforge.agent.InvestigationResult;
import ai.devforge.agent.LlmException;
import ai.devforge.agent.LlmResponse;
import ai.devforge.agent.RepoTarget;
import ai.devforge.agent.RunContext;
import ai.devforge.agent.ToolTrace;
import ai.devforge.agent.ValidationDraft;
import ai.devforge.repository.LineHit;
import ai.devforge.repository.RepoReader;
import ai.devforge.repository.SearchHit;
import ai.devforge.workspace.RepoRole;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * M1 SIMULATION of the agent. It calls no model. It does real, read-only repository search so the evidence it cites is genuine (and is
 * re-verified by the workflow), but every sentence of analysis is templated and tagged [SIMULATED]. Nothing is executed or applied.
 */
@Component
@ConditionalOnProperty(name = "devforge.agent.mode", havingValue = "simulated", matchIfMissing = true)
class SimulatedAgentSteps implements AgentSteps {
    private static final String TAG = "[SIMULATED] ";
    private static final Pattern API_PATH = Pattern.compile("(/api/[A-Za-z0-9_\\-./{}]+)");

    private final RepoReader reader;

    SimulatedAgentSteps(RepoReader reader) { this.reader = reader; }

    @Override
    public InvestigationResult investigate(RunContext ctx) {
        SimulationControls sim = SimulationControls.parse(ctx.inputText());
        pause(ctx, sim);
        if (sim.investigate() != null) {
            switch (sim.investigate()) {
                case "timeout" -> throw new LlmException(LlmException.Kind.TIMEOUT, "SIMULATED provider timeout");
                case "timeout-once" -> { if (ctx.attempt() <= 1) throw new LlmException(LlmException.Kind.TIMEOUT, "SIMULATED provider timeout (first attempt only)"); }
                case "auth-once" -> { if (ctx.attempt() <= 1) throw new LlmException(LlmException.Kind.AUTH_INVALID, "SIMULATED: the provider rejected the API key (first attempt only)"); }
                case "auth" -> throw new LlmException(LlmException.Kind.AUTH_INVALID, "SIMULATED: the provider rejected the API key");
                case "quota" -> throw new LlmException(LlmException.Kind.QUOTA_EXHAUSTED, "SIMULATED: the provider reports the quota is exhausted");
                default -> { }
            }
        }
        List<ToolTrace> trace = new ArrayList<>();
        List<FindingDraft> findings = new ArrayList<>();
        List<Hit> lead = new ArrayList<>();

        for (RepoTarget repo : ctx.repos()) {
            List<SearchHit> ranked = reader.rankFiles(repo.repositoryId(), repo.sha(), ctx.keywords(), null, List.of(".java", ".kt", ".js", ".jsx", ".ts", ".tsx", ".py", ".sql", ".xml", ".yml", ".yaml"), 5);
            trace.add(new ToolTrace("repo.rankFiles", repo.name() + " " + ctx.keywords(), ranked.size() + " candidate file(s)"));
            int rank = 0;
            for (SearchHit hit : ranked) {
                if (rank >= 3) break;
                Located loc = locate(repo, hit.path(), ctx.keywords());
                if (loc == null) continue;
                trace.add(new ToolTrace("repo.read", repo.name() + ":" + hit.path() + ":" + loc.start + "-" + loc.end, (loc.end - loc.start + 1) + " line(s)"));
                String where = repo.name() + " (" + label(repo.role()) + ")";
                findings.add(rank == 0
                    ? new FindingDraft(FindingLabel.VERIFIED, TAG + "In " + where + ", `" + hit.path() + "` line " + loc.line + " mentions the ticket's key terms: `" + loc.text + "`", Confidence.MEDIUM,
                        List.of(new EvidenceDraft(repo.repositoryId(), hit.path(), loc.start, loc.end)))
                    : new FindingDraft(FindingLabel.SUPPORTED, TAG + "`" + hit.path() + "` in " + where + " also matches the key terms and may be involved.", Confidence.LOW,
                        List.of(new EvidenceDraft(repo.repositoryId(), hit.path(), loc.start, loc.end))));
                if (rank == 0) lead.add(new Hit(repo, hit.path(), loc));
                rank++;
            }
        }
        seam(ctx, lead, findings, trace);

        List<Hypothesis> hypotheses = new ArrayList<>();
        if (!lead.isEmpty()) {
            Hit h = lead.getFirst();
            hypotheses.add(new Hypothesis(TAG + "The behaviour described is most likely decided in `" + h.path + "` (" + h.repo.name() + "); this is a lexical lead, not a confirmed cause.",
                Confidence.LOW, List.of("Key terms match this file most strongly"), List.of("No execution or runtime data was examined")));
        }
        List<String> unknowns = List.of(TAG + "No logs, database contents or runtime behaviour were available.",
            TAG + "No model reasoned about the code; run with a real provider (planned) for a genuine root-cause analysis.");
        String summary = TAG + findings.size() + " finding(s) across " + ctx.repos().size() + " repositor" + (ctx.repos().size() == 1 ? "y" : "ies") + " by keyword search only.";
        return new InvestigationResult(summary, findings, hypotheses, unknowns, trace, usage(ctx, findings.size()));
    }

    @Override
    public FixProposalDraft proposeFix(RunContext ctx, List<FindingDraft> findings) {
        pause(ctx, SimulationControls.parse(ctx.inputText()));
        List<String> files = new ArrayList<>();
        for (RepoTarget repo : ctx.repos()) {
            findings.stream().flatMap(f -> f.evidence().stream()).filter(e -> e.repositoryId().equals(repo.repositoryId())).findFirst()
                .ifPresent(e -> files.add(repo.name() + ":" + e.path()));
        }
        List<String> commands = ctx.repos().stream().filter(r -> r.testCommand() != null).map(r -> r.name() + ": " + r.testCommand()).toList();
        return new FixProposalDraft(TAG + "Proposed change touching " + files.size() + " file(s).",
            TAG + "Make the smallest change in the files below so the described behaviour matches the expected one, and add a regression test. No real analysis was performed.",
            List.of(TAG + "Guard at the API boundary instead of changing the underlying query", TAG + "Leave the code and document the behaviour"),
            files, commands,
            List.of(TAG + "Other callers of the changed code were not traced", TAG + "Behaviour for null/empty inputs is unverified"),
            List.of(TAG + "Add a failing test first that reproduces the ticket", TAG + "Run the existing suite for the changed modules"),
            List.of(TAG + "Refactoring, dependency changes, formatting-only edits"), true);
    }

    @Override
    public List<ChangeDraft> implement(RunContext ctx, FixProposalDraft proposal, String feedback) {
        pause(ctx, SimulationControls.parse(ctx.inputText()));
        List<ChangeDraft> out = new ArrayList<>();
        for (RepoTarget repo : ctx.repos()) {
            String entry = proposal.filesToChange().stream().filter(f -> f.startsWith(repo.name() + ":")).findFirst().orElse(null);
            if (entry == null) continue;
            String path = entry.substring(repo.name().length() + 1);
            List<String> lines = firstLines(repo, path, 3);
            String comment = (path.endsWith(".py") || path.endsWith(".yml") || path.endsWith(".yaml") ? "# " : path.endsWith(".xml") ? "<!-- " : "// ")
                + "DevForge SIMULATED change: no real edit was made" + (path.endsWith(".xml") ? " -->" : "");
            StringBuilder p = new StringBuilder("--- a/").append(path).append("\n+++ b/").append(path).append("\n@@ -1,").append(lines.size()).append(" +1,").append(lines.size() + 1).append(" @@\n+").append(comment).append('\n');
            lines.forEach(l -> p.append(' ').append(l).append('\n'));
            out.add(new ChangeDraft(repo.repositoryId(), repo.name(), p.toString(), List.of(path), true));
        }
        return out;
    }

    @Override
    public List<ValidationDraft> validate(RunContext ctx, List<ChangeDraft> changes) {
        SimulationControls sim = SimulationControls.parse(ctx.inputText());
        pause(ctx, sim);
        boolean fail = "fail".equals(sim.validate()) || ("fail-once".equals(sim.validate()) && ctx.attempt() <= 1);
        List<ValidationDraft> out = new ArrayList<>();
        for (ChangeDraft c : changes) {
            RepoTarget repo = ctx.repos().stream().filter(r -> r.repositoryId().equals(c.repositoryId())).findFirst().orElseThrow();
            String cmd = repo.testCommand();
            out.add(fail
                ? new ValidationDraft(c.repositoryId(), c.repositoryName(), cmd, ValidationDraft.Status.FAILED, TAG + "Failure injected by [sim:validate=" + sim.validate() + "]; no command was executed.")
                : new ValidationDraft(c.repositoryId(), c.repositoryName(), cmd, ValidationDraft.Status.SIMULATED,
                    TAG + (cmd == null ? "No test command was detected for this repository. " : "`" + cmd + "` was NOT executed. ") + "Sandboxed execution arrives in a later milestone; this is not a pass."));
        }
        return out;
    }

    // ---- helpers
    private record Located(int line, int start, int end, String text) {}
    private record Hit(RepoTarget repo, String path, Located loc) {}

    private Located locate(RepoTarget repo, String path, List<String> keywords) {
        for (String kw : keywords) {
            if (!kw.matches("[A-Za-z0-9_.-]{3,64}")) continue;
            List<LineHit> hits = reader.grepLines(repo.repositoryId(), repo.sha(), kw, path, 20).stream().filter(h -> h.path().equals(path)).toList();
            if (!hits.isEmpty()) {
                LineHit h = hits.getFirst();
                int start = Math.max(1, h.line() - 2);
                int end = start + 6;
                String read = reader.read(repo.repositoryId(), repo.sha(), path, start, end);
                int count = (int) read.lines().count();
                return new Located(h.line(), start, start + Math.max(count, 1) - 1, h.text().length() > 120 ? h.text().substring(0, 120) + "…" : h.text());
            }
        }
        return null;
    }

    /** Cross-repo link: a frontend file that calls /api/... paired with the backend line that mentions the same path segment. */
    private void seam(RunContext ctx, List<Hit> lead, List<FindingDraft> findings, List<ToolTrace> trace) {
        Hit web = lead.stream().filter(h -> h.repo.role() == RepoRole.WEB_FRONTEND || h.repo.role() == RepoRole.MOBILE).findFirst().orElse(null);
        RepoTarget backend = ctx.repos().stream().filter(r -> r.role() == RepoRole.BACKEND).findFirst().orElse(null);
        if (web == null || backend == null) return;
        String text = reader.read(web.repo.repositoryId(), web.repo.sha(), web.path, Math.max(1, web.loc.start - 20), web.loc.start + 40);
        Matcher m = API_PATH.matcher(text);
        if (!m.find()) return;
        String apiPath = m.group(1).replaceAll("[?.,;]+$", "");
        String segment = lastStaticSegment(apiPath);
        trace.add(new ToolTrace("repo.grep", backend.name() + " '" + segment + "'", "looking for backend mapping of " + apiPath));
        List<LineHit> back = segment == null ? List.of() : reader.grepLines(backend.repositoryId(), backend.sha(), segment, null, 5);
        if (back.isEmpty()) {
            findings.add(new FindingDraft(FindingLabel.HYPOTHESIS, TAG + "`" + web.path + "` calls `" + apiPath + "` but no matching mapping was found in " + backend.name() + ".", Confidence.LOW,
                List.of(new EvidenceDraft(web.repo.repositoryId(), web.path, web.loc.start, web.loc.end))));
        } else {
            LineHit b = back.getFirst();
            findings.add(new FindingDraft(FindingLabel.SUPPORTED, TAG + "Cross-repo link: `" + web.path + "` (" + web.repo.name() + ") calls `" + apiPath + "`; " + backend.name() + " mentions `" + segment + "` in `" + b.path() + "` line " + b.line() + ".",
                Confidence.LOW, List.of(new EvidenceDraft(web.repo.repositoryId(), web.path, web.loc.start, web.loc.end),
                    new EvidenceDraft(backend.repositoryId(), b.path(), Math.max(1, b.line() - 1), b.line() + 1))));
        }
    }

    private static String lastStaticSegment(String path) {
        String[] parts = path.split("/");
        for (int i = parts.length - 1; i >= 0; i--) if (parts[i].matches("[A-Za-z][A-Za-z0-9_-]{2,}")) return parts[i];
        return null;
    }

    private List<String> firstLines(RepoTarget repo, String path, int n) {
        String read = reader.read(repo.repositoryId(), repo.sha(), path, 1, n);
        List<String> lines = new ArrayList<>();
        for (String l : read.split("\n")) {
            int colon = l.indexOf(": ");
            if (colon > 0) lines.add(l.substring(colon + 2));
        }
        return lines;
    }

    private static String label(RepoRole r) { return r.name().toLowerCase().replace('_', ' '); }

    private static LlmResponse.Usage usage(RunContext ctx, int findings) {
        long in = Math.max(200, ctx.inputText().length() / 4L) * Math.max(1, ctx.repos().size()) * 6;
        return new LlmResponse.Usage(in, 120L * Math.max(1, findings));
    }

    private static void pause(RunContext ctx, SimulationControls sim) {
        long left = sim.slowMillis();
        while (left > 0) {
            if (ctx.cancelRequested().getAsBoolean()) return;
            long step = Math.min(50, left);
            try {
                Thread.sleep(step);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            left -= step;
        }
    }
}
