package ai.devforge.agent.internal.real;

import ai.devforge.agent.ChangeDraft;
import ai.devforge.agent.CredentialContext;
import ai.devforge.agent.FindingDraft;
import ai.devforge.agent.FixProposalDraft;
import ai.devforge.agent.LlmClient;
import ai.devforge.agent.LlmRequest.ToolSpec;
import ai.devforge.agent.RepoTarget;
import ai.devforge.agent.RunContext;
import ai.devforge.agent.ValidationDraft;
import ai.devforge.agent.internal.AgentProperties;
import ai.devforge.agent.internal.real.ToolLoop.Parsed;
import ai.devforge.execution.ProtectedPaths;
import ai.devforge.execution.ValidationReport;
import ai.devforge.execution.ValidationRequest;
import ai.devforge.execution.Validator;
import ai.devforge.execution.WorkCopies;
import ai.devforge.execution.WorkCopy;
import ai.devforge.platform.DomainException;
import ai.devforge.repository.RepoReader;
import ai.devforge.repository.Repositories;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The real proposal, implementation and validation steps. Stateless; one call per workflow step. */
final class FixAgents {
    static final String PROPOSAL_PROMPT = "fix-planner-v2";
    static final String IMPLEMENTER_PROMPT = "implementer-v2";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    static final ToolSpec SUBMIT_PROPOSAL = new ToolSpec("submit_proposal", "Submit the fix proposal. Call exactly once when you are done.",
        "{\"type\":\"object\",\"properties\":{\"summary\":{\"type\":\"string\",\"description\":\"What is wrong and what the change does, in two or three sentences\"},"
            + "\"recommended\":{\"type\":\"string\",\"description\":\"The recommended approach in plain steps\"},"
            + "\"alternatives\":{\"type\":\"array\",\"items\":{\"type\":\"string\"},\"description\":\"Other approaches and why they were not chosen\"},"
            + "\"files\":{\"type\":\"array\",\"items\":{\"type\":\"object\",\"properties\":{\"repo\":{\"type\":\"string\"},\"path\":{\"type\":\"string\"},\"isNew\":{\"type\":\"boolean\"},\"why\":{\"type\":\"string\"}},\"required\":[\"repo\",\"path\",\"isNew\"]}},"
            + "\"commands\":{\"type\":\"array\",\"items\":{\"type\":\"string\"}},\"risks\":{\"type\":\"array\",\"items\":{\"type\":\"string\"}},"
            + "\"testPlan\":{\"type\":\"array\",\"items\":{\"type\":\"string\"}},\"outOfScope\":{\"type\":\"array\",\"items\":{\"type\":\"string\"}}},"
            + "\"required\":[\"summary\",\"recommended\",\"files\",\"risks\",\"testPlan\"]}");

    private final LlmClient client;
    private final AgentProperties limits;
    private final RepoReader reader;
    private final Repositories repositories;
    private final WorkCopies copies;
    private final Validator validator;
    private final String plannerPrompt;
    private final String implementerPrompt;

    FixAgents(LlmClient client, AgentProperties limits, RepoReader reader, Repositories repositories, WorkCopies copies, Validator validator, String plannerPrompt, String implementerPrompt) {
        this.client = client;
        this.limits = limits;
        this.reader = reader;
        this.repositories = repositories;
        this.copies = copies;
        this.validator = validator;
        this.plannerPrompt = plannerPrompt;
        this.implementerPrompt = implementerPrompt;
    }

    FixProposalDraft propose(RunContext ctx, CredentialContext cred, List<FindingDraft> findings) {
        RepoToolbox tools = new RepoToolbox(ctx, reader, limits.maxToolOutputChars());
        var loop = new ToolLoop(client, cred, limits, ctx.agent().model(), ctx.cancelRequested());
        var res = loop.run(plannerPrompt, proposalOpening(ctx, findings), RepoToolbox.READ_TOOLS, SUBMIT_PROPOSAL, json -> ProposalParser.parse(json, ctx.repos(), reader),
            call -> { RepoToolbox.Result r = tools.execute(call); return new ToolLoop.Executed(r.text(), r.denied(), r.trace(), true); });
        ctx.usage().record("PROPOSING_FIX", res.inputTokens(), res.outputTokens(), res.cachedInputTokens());
        if (res.value() == null) throw new DomainException("AGENT_NO_PROPOSAL", org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY, "No valid fix proposal was produced: " + res.reason());
        if (!res.complete()) {
            List<String> risks = new ArrayList<>(res.value().risks());
            risks.add("This proposal was cut short (" + res.reason() + "); review it with extra care.");
            return new FixProposalDraft(res.value().summary(), res.value().recommended(), res.value().alternatives(), res.value().filesToChange(), res.value().commands(), risks, res.value().testPlan(), res.value().outOfScope(), false);
        }
        return res.value();
    }

    List<ChangeDraft> implement(RunContext ctx, CredentialContext cred, FixProposalDraft proposal, String feedback, String validationFailure) {
        try (EditToolbox tools = new EditToolbox(ctx, reader, copies, t -> Path.of(repositories.get(t.repositoryId()).localPath()), proposal.filesToChange(), limits.maxToolOutputChars())) {
            List<ToolSpec> specs = new ArrayList<>(RepoToolbox.READ_TOOLS);
            specs.addAll(EditToolbox.EDIT_TOOLS);
            var loop = new ToolLoop(client, cred, limits, ctx.agent().model(), ctx.cancelRequested());
            var res = loop.run(implementerPrompt, implementationOpening(ctx, proposal, feedback, validationFailure), specs, EditToolbox.FINISH_SPEC, FixAgents::parseDone, tools::execute);
            ctx.usage().record("IMPLEMENTATION", res.inputTokens(), res.outputTokens(), res.cachedInputTokens());
            if (!res.complete()) {
                throw new DomainException("AGENT_INCOMPLETE", org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY, "The implementation stopped before it was finished (" + res.reason() + "); nothing was kept");
            }
            Map<String, WorkCopy.Diff> diffs = tools.diffs();
            if (diffs.isEmpty()) throw new DomainException("AGENT_NO_CHANGES", org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY, "The agent finished without changing any file" + notes(res.value()));
            List<ChangeDraft> out = new ArrayList<>();
            diffs.forEach((repoName, d) -> {
                RepoTarget t = ctx.repos().stream().filter(r -> r.name().equals(repoName)).findFirst().orElseThrow();
                out.add(new ChangeDraft(t.repositoryId(), t.name(), d.patch(), d.files(), false));
            });
            return out;
        }
    }

    List<ValidationDraft> validate(RunContext ctx, List<ChangeDraft> changes) {
        List<ValidationDraft> out = new ArrayList<>();
        for (ChangeDraft c : changes) {
            RepoTarget t = ctx.repos().stream().filter(r -> r.repositoryId().equals(c.repositoryId())).findFirst().orElseThrow();
            if (c.patch() == null || c.patch().isBlank()) { out.add(draft(t, ValidationReport.notRun("There is no stored patch to validate"))); continue; }
            if (t.buildTool() == null) { out.add(draft(t, ValidationReport.notRun("No build tool was detected for " + t.name() + "; nothing was run"))); continue; }
            Set<String> approved = c.files().stream().filter(ProtectedPaths::needsApproval).collect(Collectors.toSet());
            try (WorkCopy copy = copies.open(ctx.runId() + "-val-" + t.name(), Path.of(repositories.get(t.repositoryId()).localPath()), t.sha())) {
                copy.applyPatch(c.patch(), approved);
                ValidationReport report = validator.validate(copy, new ValidationRequest(t.buildTool(), t.buildRoot() == null ? "" : t.buildRoot(), t.testCommand()), copies.cacheDir(t.repositoryId().toString()), ctx.cancelRequested());
                out.add(draft(t, report));
            } catch (WorkCopy.EditRejected e) {
                out.add(draft(t, ValidationReport.notRun("The stored patch could not be applied to a clean copy: " + e.getMessage())));
            }
        }
        return out;
    }

    private static ValidationDraft draft(RepoTarget t, ValidationReport r) {
        ValidationDraft.Status status = switch (r.status()) {
            case PASSED -> ValidationDraft.Status.PASSED;
            case FAILED -> ValidationDraft.Status.FAILED;
            case NOT_RUN -> ValidationDraft.Status.NOT_RUN;
        };
        StringBuilder s = new StringBuilder();
        if (r.tests() >= 0) s.append(r.tests()).append(" test(s): ").append(r.failures() + r.errors()).append(" failed, ").append(r.skipped()).append(" skipped");
        if (!r.failingTests().isEmpty()) s.append(s.isEmpty() ? "" : ". ").append("Failing: ").append(String.join(", ", r.failingTests()));
        if (!r.note().isBlank()) s.append(s.isEmpty() ? "" : ". ").append(r.note());
        if (s.isEmpty()) s.append(status == ValidationDraft.Status.PASSED ? "The command succeeded" : "The command failed");
        if (!r.outputTail().isBlank()) s.append("\n--- output (end) ---\n").append(r.outputTail());
        return new ValidationDraft(t.repositoryId(), t.name(), r.command(), status, s.toString());
    }

    private static Parsed<EditToolbox.Done> parseDone(String json) {
        try {
            JsonNode a = JSON.readTree(json == null ? "{}" : json);
            String summary = a.path("summary").asString("").strip();
            if (summary.isEmpty()) return Parsed.bad("`summary` is required.");
            List<String> notes = new ArrayList<>();
            a.path("notes").forEach(n -> notes.add(n.asString("")));
            return Parsed.ok(new EditToolbox.Done(summary, notes));
        } catch (RuntimeException e) {
            return Parsed.bad("The arguments were not valid JSON.");
        }
    }

    private static String notes(EditToolbox.Done d) { return d == null || d.notes().isEmpty() ? "" : " (" + String.join("; ", d.notes()) + ")"; }

    private static String repoList(RunContext ctx) {
        return ctx.repos().stream().map(r -> "- " + r.name() + " (" + r.role().name().toLowerCase().replace('_', ' ') + ")" + (r.testCommand() == null ? "" : ", tests: " + r.testCommand())).collect(Collectors.joining("\n"));
    }

    private static String fence(String s) { return s.replace("</", "< /"); }

    private static String proposalOpening(RunContext ctx, List<FindingDraft> findings) {
        String f = findings.stream().map(x -> "- [" + x.label() + "] " + x.text() + (x.evidence().isEmpty() ? "" : " (" + x.evidence().stream().map(e -> e.path() + ":" + e.startLine() + "-" + e.endLine()).collect(Collectors.joining(", ")) + ")")).collect(Collectors.joining("\n"));
        return "Propose a minimal fix for the problem below.\n\nRepositories you may read (pass the name as `repo`):\n" + repoList(ctx)
            + "\n\n<ticket>\n" + fence(ctx.inputText()) + "\n</ticket>\n\nWhat the investigation concluded (derived from the code, may contain mistakes):\n<findings>\n" + fence(f) + "\n</findings>";
    }

    private static String implementationOpening(RunContext ctx, FixProposalDraft p, String feedback, String validationFailure) {
        StringBuilder sb = new StringBuilder("Implement the approved proposal below.\n\nRepositories (pass the name as `repo`):\n").append(repoList(ctx));
        sb.append("\n\n<ticket>\n").append(fence(ctx.inputText())).append("\n</ticket>\n\n<proposal>\nSummary: ").append(fence(p.summary())).append("\nApproach: ").append(fence(p.recommended()))
            .append("\nFiles you may change:\n").append(p.filesToChange().stream().map(f -> "- " + f).collect(Collectors.joining("\n"))).append("\nTest plan: ").append(fence(String.join("; ", p.testPlan()))).append("\n</proposal>");
        if (feedback != null && !feedback.isBlank()) sb.append("\n\n<reviewer_feedback>\n").append(fence(feedback)).append("\n</reviewer_feedback>");
        if (validationFailure != null && !validationFailure.isBlank()) sb.append("\n\n<validation_failure>\n").append(fence(validationFailure)).append("\n</validation_failure>");
        return sb.toString();
    }
}
