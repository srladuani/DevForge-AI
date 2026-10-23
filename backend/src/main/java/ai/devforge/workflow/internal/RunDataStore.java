package ai.devforge.workflow.internal;

import ai.devforge.agent.Confidence;
import ai.devforge.agent.EvidenceDraft;
import ai.devforge.agent.FindingDraft;
import ai.devforge.agent.FindingLabel;
import ai.devforge.platform.Hashes;
import ai.devforge.workflow.RunViews.ArtifactView;
import ai.devforge.workflow.RunViews.ChangeView;
import ai.devforge.workflow.RunViews.EvidenceView;
import ai.devforge.workflow.RunViews.FindingView;
import ai.devforge.workflow.RunViews.InputView;
import ai.devforge.workflow.RunViews.RepoView;
import ai.devforge.workflow.RunViews.ValidationView;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** SQL for everything a run produces: inputs, findings and their evidence, artifacts, patches, validations, model usage. */
@Component
class RunDataStore {
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};
    private static final TypeReference<List<String>> STRINGS = new TypeReference<>() {};

    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final io.micrometer.core.instrument.MeterRegistry meters;

    RunDataStore(JdbcClient jdbc, JsonMapper json, io.micrometer.core.instrument.MeterRegistry meters) {
        this.jdbc = jdbc;
        this.json = json;
        this.meters = meters;
    }

    // ---- inputs and repos
    UUID addInput(UUID runId, String kind, String sourceRef, String rawText) {
        UUID id = UUID.randomUUID();
        jdbc.sql("insert into run_input (id, run_id, kind, source_ref, raw_text, raw_hash) values (?,?,?,?,?,?)").params(id, runId, kind, sourceRef, rawText, Hashes.sha256(rawText)).update();
        return id;
    }

    /** Records the text a ticket fetch produced for an input that was created without any. */
    void setInputText(UUID inputId, String rawText) {
        jdbc.sql("update run_input set raw_text = ?, raw_hash = ? where id = ?").params(rawText, Hashes.sha256(rawText), inputId).update();
    }

    void setNormalized(UUID inputId, Map<String, Object> normalized) {
        jdbc.sql("update run_input set normalized = ? where id = ?").params(json.writeValueAsString(normalized), inputId).update();
    }

    List<InputView> inputs(UUID runId) {
        return jdbc.sql("select id, kind, source_ref, raw_text, normalized, created_at from run_input where run_id = ? order by created_at, id").param(runId)
            .query((rs, i) -> new InputView(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4),
                rs.getString(5) == null ? null : json.readValue(rs.getString(5), MAP), rs.getTimestamp(6).toInstant())).list();
    }

    void addRepo(UUID runId, UUID repoId, String name, String role, String sha) {
        jdbc.sql("insert into run_repo (run_id, repository_id, repo_name, repo_role, base_sha) values (?,?,?,?,?)").params(runId, repoId, name, role, sha).update();
    }

    List<RepoView> repos(UUID runId) {
        return jdbc.sql("select repository_id, repo_name, repo_role, base_sha from run_repo where run_id = ? order by repo_name").param(runId)
            .query((rs, i) -> new RepoView(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4))).list();
    }

    // ---- findings and evidence
    record StoredEvidence(UUID id, boolean verified) {}

    UUID addEvidence(UUID runId, UUID repoId, String repoName, String sha, String path, int start, int end, String hash, String excerpt, boolean verified, String problem) {
        UUID id = UUID.randomUUID();
        jdbc.sql("insert into evidence (id, run_id, repository_id, repo_name, sha, path, start_line, end_line, content_hash, excerpt, verified, problem) values (?,?,?,?,?,?,?,?,?,?,?,?)")
            .params(id, runId, repoId, repoName, sha, path, start, end, hash, excerpt, verified, problem).update();
        return id;
    }

    UUID addFinding(UUID runId, String step, FindingLabel label, FindingLabel requested, String downgradeReason, String text, Confidence confidence, List<UUID> evidenceIds) {
        UUID id = UUID.randomUUID();
        jdbc.sql("insert into finding (id, run_id, step, label, requested_label, downgrade_reason, text, confidence) values (?,?,?,?,?,?,?,?)")
            .params(id, runId, step, label.name(), requested.name(), downgradeReason, text, confidence.name()).update();
        for (UUID e : evidenceIds) jdbc.sql("insert into finding_evidence (finding_id, evidence_id) values (?,?)").params(id, e).update();
        return id;
    }

    List<FindingView> findings(UUID runId) {
        Map<UUID, EvidenceView> evidence = new LinkedHashMap<>();
        jdbc.sql("select id, repository_id, repo_name, sha, path, start_line, end_line, content_hash, excerpt, verified, problem from evidence where run_id = ?").param(runId)
            .query((rs, i) -> new EvidenceView(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3), rs.getString(4), rs.getString(5), rs.getInt(6), rs.getInt(7),
                rs.getString(8), rs.getString(9), rs.getBoolean(10), rs.getString(11))).list().forEach(e -> evidence.put(e.id(), e));
        Map<UUID, List<UUID>> links = new LinkedHashMap<>();
        jdbc.sql("select fe.finding_id, fe.evidence_id from finding_evidence fe join finding f on f.id = fe.finding_id where f.run_id = ?").param(runId)
            .query((rs, i) -> new UUID[] {rs.getObject(1, UUID.class), rs.getObject(2, UUID.class)}).list().forEach(p -> links.computeIfAbsent(p[0], k -> new ArrayList<>()).add(p[1]));
        return jdbc.sql("select id, step, label, requested_label, downgrade_reason, text, confidence from finding where run_id = ? order by created_at, id").param(runId)
            .query((rs, i) -> {
                UUID id = rs.getObject(1, UUID.class);
                return new FindingView(id, rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7),
                    links.getOrDefault(id, List.of()).stream().map(evidence::get).toList());
            }).list();
    }

    /** Findings as drafts again (label as finally stored, evidence refs), for the next agent step. */
    List<FindingDraft> findingDrafts(UUID runId) {
        return findings(runId).stream().map(f -> new FindingDraft(FindingLabel.valueOf(f.label()), f.text(), Confidence.valueOf(f.confidence()),
            f.evidence().stream().map(e -> new EvidenceDraft(e.repositoryId(), e.path(), e.startLine(), e.endLine())).toList())).toList();
    }

    // ---- artifacts
    record StoredArtifact(UUID id, String content, String hash) {}

    StoredArtifact addArtifact(UUID runId, String type, Object content) {
        jdbc.sql("update run_artifact set current = false where run_id = ? and type = ?").params(runId, type).update();
        UUID id = UUID.randomUUID();
        String text = json.writeValueAsString(content);
        String hash = Hashes.sha256(text);
        jdbc.sql("insert into run_artifact (id, run_id, type, content, content_hash) values (?,?,?,?,?)").params(id, runId, type, text, hash).update();
        return new StoredArtifact(id, text, hash);
    }

    Optional<StoredArtifact> currentArtifact(UUID runId, String type) {
        return jdbc.sql("select id, content, content_hash from run_artifact where run_id = ? and type = ? and current order by created_at desc limit 1").params(runId, type)
            .query((rs, i) -> new StoredArtifact(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3))).optional();
    }

    <T> Optional<T> currentArtifactAs(UUID runId, String type, Class<T> as) {
        return currentArtifact(runId, type).map(a -> json.readValue(a.content(), as));
    }

    List<ArtifactView> artifacts(UUID runId) {
        return jdbc.sql("select id, type, content, content_hash, current, created_at from run_artifact where run_id = ? order by created_at").param(runId)
            .query((rs, i) -> new ArtifactView(rs.getObject(1, UUID.class), rs.getString(2), json.readValue(rs.getString(3), MAP), rs.getString(4), rs.getBoolean(5), rs.getTimestamp(6).toInstant())).list();
    }

    // ---- changes and validations
    UUID addChange(UUID runId, UUID repoId, String repoName, String sha, String patch, List<String> files, boolean simulated, int attempt) {
        UUID id = UUID.randomUUID();
        jdbc.sql("insert into code_change (id, run_id, repository_id, repo_name, base_sha, patch, patch_hash, files, simulated, attempt) values (?,?,?,?,?,?,?,?,?,?)")
            .params(id, runId, repoId, repoName, sha, patch, Hashes.sha256(patch), json.writeValueAsString(files), simulated, attempt).update();
        return id;
    }

    int latestChangeAttempt(UUID runId) {
        return jdbc.sql("select coalesce(max(attempt), 0) from code_change where run_id = ?").param(runId).query(Integer.class).single();
    }

    record StoredChange(UUID id, UUID repositoryId, String repoName, String sha) {}

    List<StoredChange> changesOfAttempt(UUID runId, int attempt) {
        return jdbc.sql("select id, repository_id, repo_name, base_sha from code_change where run_id = ? and attempt = ? order by repo_name").params(runId, attempt)
            .query((rs, i) -> new StoredChange(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3), rs.getString(4))).list();
    }

    List<ChangeView> changes(UUID runId) {
        return jdbc.sql("select id, repository_id, repo_name, base_sha, patch, patch_hash, files, simulated, attempt from code_change where run_id = ? order by attempt, repo_name").param(runId)
            .query((rs, i) -> new ChangeView(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6),
                json.readValue(rs.getString(7), STRINGS), rs.getBoolean(8), rs.getInt(9))).list();
    }

    void addValidation(UUID runId, UUID changeId, String repoName, String command, String status, String summary, int attempt) {
        jdbc.sql("insert into validation_run (id, run_id, code_change_id, repo_name, command, status, summary, attempt) values (?,?,?,?,?,?,?,?)")
            .params(UUID.randomUUID(), runId, changeId, repoName, command, status, summary, attempt).update();
    }

    List<ValidationView> validations(UUID runId) {
        return jdbc.sql("select id, code_change_id, repo_name, command, status, summary, attempt from validation_run where run_id = ? order by created_at").param(runId)
            .query((rs, i) -> new ValidationView(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6), rs.getInt(7))).list();
    }

    String latestReviewFeedback(UUID runId) {
        return jdbc.sql("select raw_text from run_input where run_id = ? and kind = 'REVIEW_FEEDBACK' order by created_at desc limit 1").param(runId).query(String.class).optional().orElse(null);
    }

    // ---- usage
    void addModelCall(UUID runId, String step, UUID userId, String provider, String model, long in, long out, BigDecimal cost, boolean simulated) {
        jdbc.sql("insert into model_call (id, run_id, step, user_id, provider, model, input_tokens, output_tokens, cost_usd, simulated) values (?,?,?,?,?,?,?,?,?,?)")
            .params(UUID.randomUUID(), runId, step, userId, provider, model, in, out, cost, simulated).update();
        String sim = String.valueOf(simulated);
        meters.counter("devforge.model.tokens", "provider", provider, "direction", "input", "simulated", sim).increment(in);
        meters.counter("devforge.model.tokens", "provider", provider, "direction", "output", "simulated", sim).increment(out);
    }

    // ---- idempotent re-runs: a step that is executed again after a crash replaces its own earlier output
    void clearInvestigationOutputs(UUID runId) {
        jdbc.sql("delete from finding where run_id = ? and step = 'INVESTIGATION'").param(runId).update();
        jdbc.sql("delete from evidence where run_id = ?").param(runId).update();
    }

    void deleteChangesOfAttempt(UUID runId, int attempt) {
        jdbc.sql("delete from code_change where run_id = ? and attempt = ?").params(runId, attempt).update(); // validations cascade
    }

    void deleteValidationsOfAttempt(UUID runId, int attempt) {
        jdbc.sql("delete from validation_run where run_id = ? and attempt = ?").params(runId, attempt).update();
    }
}
