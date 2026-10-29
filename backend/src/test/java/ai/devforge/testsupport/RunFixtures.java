package ai.devforge.testsupport;

import ai.devforge.platform.Hashes;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Inserts a run that is already waiting for approval, with the fix-proposal artifact its approval will be bound to. Test-only. */
public final class RunFixtures {
    private RunFixtures() {}

    public record AwaitingApproval(UUID runId, String proposalHash) {}

    public static AwaitingApproval runAwaitingApproval(JdbcClient jdbc, UUID workspaceId, UUID ownerId) {
        UUID runId = UUID.randomUUID();
        jdbc.sql("""
                insert into run (id, workspace_id, type, mode, status, owner_user_id, title, request_hash, agent_provider, agent_model, await_reason, resume_state)
                values (?, ?, 'support-investigation', 'FIX_AND_PATCH', 'AWAITING_APPROVAL', ?, 'fixture run', 'x', 'ANTHROPIC', 'claude-sonnet-5-5', 'APPROVAL', 'FIX_PROPOSAL_AVAILABLE')""")
            .params(runId, workspaceId, ownerId).update();
        String content = "{\"summary\":\"s\",\"recommended\":\"r\",\"alternatives\":[],\"filesToChange\":[],\"commands\":[],\"risks\":[],\"testPlan\":[],\"outOfScope\":[],\"simulated\":true}";
        String hash = Hashes.sha256(content);
        jdbc.sql("insert into run_artifact (id, run_id, type, content, content_hash) values (?, ?, 'FIX_PROPOSAL', ?, ?)").params(UUID.randomUUID(), runId, content, hash).update();
        return new AwaitingApproval(runId, hash);
    }
}
