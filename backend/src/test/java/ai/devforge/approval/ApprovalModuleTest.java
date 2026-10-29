package ai.devforge.approval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.devforge.IntegrationTest;
import ai.devforge.identity.Users;
import ai.devforge.testsupport.Api;
import ai.devforge.testsupport.RunFixtures;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.event.EventListener;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;

@RecordApplicationEvents
@Import(ApprovalModuleTest.Boom.class)
class ApprovalModuleTest extends IntegrationTest {
    /** A listener that refuses to honour a decision, to prove the decision rolls back with it. */
    static class Boom {
        @Bean
        Object boomListener() {
            return new Object() {
                @EventListener
                void on(ApprovalDecided e) {
                    if ("boom".equals(e.comment())) throw new IllegalStateException("cannot honour decision");
                }
            };
        }
    }

    @Autowired Approvals approvals;
    @Autowired Users users;
    @Autowired ApplicationEvents events;
    Api api;
    final String suffix = UUID.randomUUID().toString().substring(0, 8);
    String ws;

    String u(String n) { return n + "-" + suffix + "@example.test"; }
    UUID id(String n) { return users.findByEmail(u(n)).orElseThrow().id(); }

    @BeforeEach
    void setUp() throws Exception {
        api = new Api(mvc);
        for (String n : new String[] {"owner", "approver", "operator", "viewer", "outsider"}) api.get(u(n), "/api/v1/workspaces").andExpect(status().isOk());
        ws = Api.json(api.post(u("owner"), "/api/v1/workspaces", "{\"name\":\"appr\",\"kind\":\"SUPPORT\"}").andExpect(status().isCreated()), "$.id");
        for (String[] m : new String[][] {{"approver", "APPROVER"}, {"operator", "OPERATOR"}, {"viewer", "VIEWER"}}) {
            api.put(u("owner"), "/api/v1/workspaces/" + ws + "/members", "{\"email\":\"" + u(m[0]) + "\",\"role\":\"" + m[1] + "\"}").andExpect(status().isOk());
        }
    }

    /** A real run waiting for approval of a real proposal; the approval is bound to that proposal's hash. */
    RunFixtures.AwaitingApproval run() {
        return RunFixtures.runAwaitingApproval(jdbc, UUID.fromString(ws), id("owner"));
    }

    ApprovalView request(RunFixtures.AwaitingApproval r) {
        return approvals.request(UUID.fromString(ws), r.runId(), ApprovalKind.IMPLEMENT, r.proposalHash(), "{\"files\":[\"a\"]}", id("operator"));
    }

    String decision(String hash, String d, String comment) {
        return "{\"decision\":\"" + d + "\",\"artifactHash\":\"" + hash + "\",\"comment\":" + (comment == null ? "null" : "\"" + comment + "\"") + "}";
    }

    @Test
    void anApprovalOnlyCountsForTheExactArtifactItWasGivenFor() throws Exception {
        var r = run();
        ApprovalView a = request(r);
        api.post(u("approver"), "/api/v1/approvals/" + a.id() + "/decision", decision("sha256:bbb", "APPROVE", null))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ARTIFACT_CHANGED"));
        assertThat(approvals.hasValidApproval(a.runId(), ApprovalKind.IMPLEMENT, r.proposalHash())).isFalse();

        api.post(u("approver"), "/api/v1/approvals/" + a.id() + "/decision", decision(r.proposalHash(), "APPROVE", "looks fine")).andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("APPROVED"));
        assertThat(approvals.hasValidApproval(a.runId(), ApprovalKind.IMPLEMENT, r.proposalHash())).isTrue();
        assertThat(approvals.hasValidApproval(a.runId(), ApprovalKind.IMPLEMENT, "sha256:bbb")).as("approval does not transfer to a changed artifact").isFalse();
        assertThat(approvals.hasValidApproval(a.runId(), ApprovalKind.PUSH, r.proposalHash())).as("approval does not transfer to another kind of action").isFalse();
        assertThat(events.stream(ApprovalDecided.class).filter(e -> e.approvalId().equals(a.id())).count()).isEqualTo(1);

        api.post(u("approver"), "/api/v1/approvals/" + a.id() + "/decision", decision(r.proposalHash(), "REJECT", null)).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("APPROVAL_NOT_PENDING"));
    }

    @Test
    void onlyApproversAndAdminsMayDecide() throws Exception {
        var r = run();
        ApprovalView a = request(r);
        for (String who : new String[] {"viewer", "operator"}) {
            api.post(u(who), "/api/v1/approvals/" + a.id() + "/decision", decision(r.proposalHash(), "APPROVE", null)).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("INSUFFICIENT_ROLE"));
        }
        api.post(u("outsider"), "/api/v1/approvals/" + a.id() + "/decision", decision(r.proposalHash(), "APPROVE", null)).andExpect(status().isNotFound());
        assertThat(approvals.find(a.id()).orElseThrow().status()).isEqualTo(ApprovalStatus.PENDING);
        api.post(u("owner"), "/api/v1/approvals/" + a.id() + "/decision", decision(r.proposalHash(), "REJECT", "not now")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("REJECTED"));
    }

    @Test
    void projectPolicyCanForbidSelfApproval() throws Exception {
        api.patch(u("owner"), "/api/v1/workspaces/" + ws, "{\"policy\":{\"allowedProviders\":[],\"requireDifferentApprover\":true,\"maxRunCostUsd\":2,\"maxConcurrentRuns\":2}}").andExpect(status().isOk());
        var r = run();
        ApprovalView byOwner = approvals.request(UUID.fromString(ws), r.runId(), ApprovalKind.IMPLEMENT, r.proposalHash(), "{}", id("owner"));
        api.post(u("owner"), "/api/v1/approvals/" + byOwner.id() + "/decision", decision(r.proposalHash(), "APPROVE", null)).andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("SELF_APPROVAL_NOT_ALLOWED"));
        api.post(u("approver"), "/api/v1/approvals/" + byOwner.id() + "/decision", decision(r.proposalHash(), "APPROVE", null)).andExpect(status().isOk());
    }

    @Test
    void aNewRequestSupersedesTheOldOneAndExpiredApprovalsCannotBeUsed() throws Exception {
        UUID run = UUID.randomUUID();
        ApprovalView first = approvals.request(UUID.fromString(ws), run, ApprovalKind.IMPLEMENT, "sha256:v1", "{}", id("operator"));
        ApprovalView second = approvals.request(UUID.fromString(ws), run, ApprovalKind.IMPLEMENT, "sha256:v2", "{}", id("operator"));
        assertThat(approvals.find(first.id()).orElseThrow().status()).isEqualTo(ApprovalStatus.SUPERSEDED);
        api.post(u("approver"), "/api/v1/approvals/" + first.id() + "/decision", decision("sha256:v1", "APPROVE", null)).andExpect(status().isConflict());

        jdbc.sql("update approval set expires_at = now() - interval '1 minute' where id = ?").param(second.id()).update();
        api.post(u("approver"), "/api/v1/approvals/" + second.id() + "/decision", decision("sha256:v2", "APPROVE", null)).andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("APPROVAL_NOT_PENDING"));
        assertThat(approvals.find(second.id()).orElseThrow().status()).isEqualTo(ApprovalStatus.EXPIRED);
    }

    @Test
    void ifAListenerCannotHonourTheDecisionTheDecisionDoesNotStick() throws Exception {
        var r = run();
        ApprovalView a = request(r);
        api.post(u("approver"), "/api/v1/approvals/" + a.id() + "/decision", decision(r.proposalHash(), "APPROVE", "boom")).andExpect(status().isInternalServerError());
        assertThat(approvals.find(a.id()).orElseThrow().status()).as("rolled back with the failed listener").isEqualTo(ApprovalStatus.PENDING);
        assertThat(approvals.hasValidApproval(a.runId(), ApprovalKind.IMPLEMENT, r.proposalHash())).isFalse();
    }

    @Test
    void membersCanListApprovalsForTheirProjectOnly() throws Exception {
        ApprovalView a = request(run());
        api.get(u("viewer"), "/api/v1/workspaces/" + ws + "/approvals?runId=" + a.runId()).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1));
        api.get(u("outsider"), "/api/v1/workspaces/" + ws + "/approvals").andExpect(status().isNotFound());
    }
}
