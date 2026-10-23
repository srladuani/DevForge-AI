package ai.devforge.workflow.internal;

import ai.devforge.agent.FixProposalDraft;
import ai.devforge.identity.CurrentUser;
import ai.devforge.identity.Users;
import ai.devforge.platform.DomainException;
import ai.devforge.workflow.RunStatus;
import ai.devforge.workflow.RunViews.ChangeView;
import ai.devforge.workflow.RunViews.ExportRepoView;
import ai.devforge.workflow.RunViews.ExportView;
import ai.devforge.workflow.RunViews.InputView;
import ai.devforge.workflow.RunViews.SecretFinding;
import ai.devforge.workflow.RunViews.ValidationView;
import ai.devforge.workspace.WorkspaceRole.Permission;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Takes a finished change out of the platform without any Git host: a branch name, a {@code git am} patch per repository (authored by the run
 * owner, who takes responsibility for it) and a PR description. Local only; nothing is pushed. Simulated changes and patches with credentials
 * in them are refused.
 */
@Service
class ExportService {
    private static final DateTimeFormatter RFC_2822 = DateTimeFormatter.ofPattern("EEE, d MMM yyyy HH:mm:ss Z", Locale.ENGLISH);
    private final RunService runs;
    private final RunDataStore data;
    private final Users users;

    ExportService(RunService runs, RunDataStore data, Users users) {
        this.runs = runs;
        this.data = data;
        this.users = users;
    }

    ExportView view(UUID runId, CurrentUser user) {
        Prepared p = prepare(runId, user);
        List<ExportRepoView> repos = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        boolean blocked = false;
        for (ChangeView c : p.changes()) {
            List<SecretFinding> secrets = PatchScanner.scan(c.patch());
            boolean block = secrets.stream().anyMatch(s -> s.severity().equals("BLOCK"));
            blocked |= block;
            String status = p.validations().stream().filter(v -> v.codeChangeId().equals(c.id())).map(ValidationView::status).findFirst().orElse(null);
            if (status == null || !status.equals("PASSED")) warnings.add(c.repoName() + ": " + (status == null ? "no check was recorded" : "the check result is " + status.toLowerCase().replace('_', ' ')) + "; review the change with extra care");
            if (secrets.stream().anyMatch(s -> s.severity().equals("WARN"))) warnings.add(c.repoName() + ": the patch adds lines that look like credential assignments; check them");
            if (block) warnings.add(c.repoName() + ": the patch appears to contain a credential, so it cannot be exported. Remove it and run the change again");
            repos.add(new ExportRepoView(c.id(), c.repoName(), p.branch(), p.subject(), c.files(), fileName(p.branch(), c.repoName()), status, secrets, !block));
        }
        String pr = PrDescription.build(runId.toString(), p.ticketKey(), p.subject(), p.proposal(), data.findings(runId), p.changes(), p.validations());
        return new ExportView(runId, p.branch(), pr, repos, warnings, blocked);
    }

    /** One mbox-format patch; apply with {@code git am} on a checkout of the base commit. */
    String patchFile(UUID runId, UUID changeId, CurrentUser user) {
        Prepared p = prepare(runId, user);
        ChangeView c = p.changes().stream().filter(x -> x.id().equals(changeId)).findFirst().orElseThrow(() -> DomainException.notFound("CHANGE_NOT_FOUND", "No such change in this run"));
        if (PatchScanner.scan(c.patch()).stream().anyMatch(s -> s.severity().equals("BLOCK"))) {
            throw DomainException.unprocessable("SECRET_IN_PATCH", "The patch appears to contain a credential, so it cannot be exported");
        }
        String body = p.proposal() == null ? "" : p.proposal().summary() + "\n\n" + p.proposal().recommended() + "\n";
        String refs = p.ticketKey() == null ? "" : "\nRefs: " + p.ticketKey();
        String checks = p.validations().stream().filter(v -> v.codeChangeId().equals(c.id())).map(v -> "Checks: " + v.status().toLowerCase().replace('_', ' ')).findFirst().orElse("Checks: none recorded");
        CurrentUser author = users.find(runs.load(runId, user, Permission.VIEW).ownerUserId()).orElse(user);
        String patch = c.patch().endsWith("\n") ? c.patch() : c.patch() + "\n";
        return "From " + "0".repeat(40) + " Mon Sep 17 00:00:00 2001\nFrom: " + author.displayName().replaceAll("[<>\\r\\n]", "") + " <" + author.email() + ">\nDate: " + ZonedDateTime.now(ZoneOffset.UTC).format(RFC_2822)
            + "\nSubject: [PATCH] " + p.subject().replaceAll("[\\r\\n]", " ") + "\n\n" + body.strip() + refs + "\n" + checks + "\nGenerated-by: DevForge AI (run " + runId + ")\n---\n" + patch;
    }

    String fileNameOf(UUID runId, UUID changeId, CurrentUser user) {
        Prepared p = prepare(runId, user);
        ChangeView c = p.changes().stream().filter(x -> x.id().equals(changeId)).findFirst().orElseThrow(() -> DomainException.notFound("CHANGE_NOT_FOUND", "No such change in this run"));
        return fileName(p.branch(), c.repoName());
    }

    private record Prepared(String branch, String subject, String ticketKey, FixProposalDraft proposal, List<ChangeView> changes, List<ValidationView> validations) {}

    private Prepared prepare(UUID runId, CurrentUser user) {
        RunRow run = runs.load(runId, user, Permission.VIEW);
        if (run.status() != RunStatus.AWAITING_REVIEW && run.status() != RunStatus.COMPLETED) {
            throw DomainException.conflict("NOT_READY_TO_EXPORT", "A change can be exported once it has been prepared and is awaiting review, or accepted");
        }
        int attempt = data.latestChangeAttempt(runId);
        List<ChangeView> changes = data.changes(runId).stream().filter(c -> c.attempt() == attempt).toList();
        if (changes.isEmpty()) throw DomainException.conflict("NO_CHANGES", "This run has no change to export");
        if (changes.stream().anyMatch(ChangeView::simulated)) throw DomainException.unprocessable("SIMULATED_CHANGE", "This change was produced by the built-in simulation and is not a real change, so it cannot be exported");
        List<ValidationView> validations = data.validations(runId).stream().filter(v -> v.attempt() == attempt).toList();
        FixProposalDraft proposal = data.currentArtifactAs(runId, "FIX_PROPOSAL", FixProposalDraft.class).orElse(null);
        String key = data.inputs(runId).stream().filter(i -> i.kind().equals("TICKET")).map(InputView::sourceRef).filter(java.util.Objects::nonNull).findFirst().orElse(null);
        String summary = proposal == null ? run.title() : firstSentence(proposal.summary());
        String subject = (key == null ? "" : key + ": ") + clip(summary, 72);
        String slug = slug(summary);
        String branch = "devforge/" + (key == null ? runId.toString().substring(0, 8) : key.toLowerCase(Locale.ROOT)) + (slug.isEmpty() ? "" : "-" + slug);
        return new Prepared(branch, subject, key, proposal, changes, validations);
    }

    private static String fileName(String branch, String repo) { return branch.replace('/', '-') + "-" + repo.replaceAll("[^A-Za-z0-9._-]", "_") + ".patch"; }

    private static String firstSentence(String s) {
        String t = s.replaceAll("\\s+", " ").strip();
        int dot = t.indexOf(". ");
        return dot > 10 ? t.substring(0, dot) : t.endsWith(".") ? t.substring(0, t.length() - 1) : t;
    }

    private static String clip(String s, int n) { return s.length() <= n ? s : s.substring(0, n - 1).stripTrailing() + "…"; }

    private static String slug(String s) {
        String t = s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
        t = t.length() > 40 ? t.substring(0, 40).replaceAll("-[^-]*$", "") : t;
        return t.replaceAll("^-|-$", "");
    }

}
