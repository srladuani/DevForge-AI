package ai.devforge.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.devforge.agent.AgentSteps;
import ai.devforge.agent.ChangeDraft;
import ai.devforge.agent.Confidence;
import ai.devforge.agent.EvidenceDraft;
import ai.devforge.agent.FindingDraft;
import ai.devforge.agent.FindingLabel;
import ai.devforge.agent.FixProposalDraft;
import ai.devforge.agent.InvestigationResult;
import ai.devforge.agent.LlmResponse;
import ai.devforge.agent.RunContext;
import ai.devforge.agent.ValidationDraft;
import com.jayway.jsonpath.JsonPath;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

/**
 * The agent is untrusted. This one lies in every way the server must catch: it cites files that do not exist, lines past the end, secrets,
 * and repositories the run was never given, and it tries to change a repository outside the run.
 */
@Import(EvidenceTrustTest.LyingAgentConfig.class)
@org.springframework.test.context.TestPropertySource(properties = "devforge.workflow.queue-name=evidence-trust")
class EvidenceTrustTest extends WorkflowTestBase {
    static volatile UUID otherRepoInProject;

    @TestConfiguration
    static class LyingAgentConfig {
        @Bean
        @Primary
        AgentSteps lyingAgent() {
            return new AgentSteps() {
                @Override
                public InvestigationResult investigate(RunContext ctx) {
                    UUID api = ctx.repos().getFirst().repositoryId();
                    String bo = "src/main/java/com/acme/video/TrainingVideoBO.java";
                    List<FindingDraft> f = List.of(
                        new FindingDraft(FindingLabel.VERIFIED, "honest: real lines", Confidence.HIGH, List.of(new EvidenceDraft(api, bo, 6, 7))),
                        new FindingDraft(FindingLabel.VERIFIED, "claims a file that does not exist", Confidence.HIGH, List.of(new EvidenceDraft(api, "src/main/java/Ghost.java", 1, 3))),
                        new FindingDraft(FindingLabel.VERIFIED, "one real location, one invented", Confidence.HIGH, List.of(new EvidenceDraft(api, bo, 6, 7), new EvidenceDraft(api, bo, 400, 410))),
                        new FindingDraft(FindingLabel.SUPPORTED, "cites lines past the end of a real file", Confidence.MEDIUM, List.of(new EvidenceDraft(api, bo, 50, 60))),
                        new FindingDraft(FindingLabel.VERIFIED, "cites a secret file", Confidence.HIGH, List.of(new EvidenceDraft(api, ".env", 1, 1))),
                        new FindingDraft(FindingLabel.VERIFIED, "cites a repository this run was not given", Confidence.HIGH, List.of(new EvidenceDraft(otherRepoInProject, "src/App.js", 1, 1))),
                        new FindingDraft(FindingLabel.VERIFIED, "traverses out of the repository", Confidence.HIGH, List.of(new EvidenceDraft(api, "../../etc/passwd", 1, 1))),
                        new FindingDraft(FindingLabel.HYPOTHESIS, "an honest guess", Confidence.LOW, List.of()),
                        new FindingDraft(FindingLabel.UNKNOWN, "an honest gap", Confidence.LOW, List.of()));
                    return new InvestigationResult("lying", f, List.of(), List.of(), List.of(), new LlmResponse.Usage(1, 1));
                }

                @Override
                public FixProposalDraft proposeFix(RunContext ctx, List<FindingDraft> findings) {
                    return new FixProposalDraft("s", "r", List.of(), List.of(ctx.repos().getFirst().name() + ":src/main/java/com/acme/video/TrainingVideoBO.java"), List.of(), List.of(), List.of(), List.of(), true);
                }

                @Override
                public List<ChangeDraft> implement(RunContext ctx, FixProposalDraft proposal, String feedback) {
                    return List.of(new ChangeDraft(otherRepoInProject, "admin-portal", "--- a/x\n+++ b/x\n", List.of("x"), true));
                }

                @Override
                public List<ValidationDraft> validate(RunContext ctx, List<ChangeDraft> changes) { return List.of(); }
            };
        }
    }

    @Test
    void theServerDowngradesEveryClaimItCannotVerifyAndKeepsTheHonestOnes() throws Exception {
        otherRepoInProject = UUID.fromString(webId);
        String run = Api(createRunOnlyBackend());
        awaitStatus(run, "FINDINGS_AVAILABLE");
        String json = api.get(u("owner"), "/api/v1/runs/" + run + "/findings").andReturn().getResponse().getContentAsString();

        assertThat(label(json, "honest: real lines")).isEqualTo("VERIFIED");
        assertThat(label(json, "claims a file that does not exist")).isEqualTo("HYPOTHESIS");
        assertThat(label(json, "one real location, one invented")).isEqualTo("SUPPORTED");
        assertThat(label(json, "cites lines past the end of a real file")).isEqualTo("HYPOTHESIS");
        assertThat(label(json, "cites a secret file")).isEqualTo("HYPOTHESIS");
        assertThat(label(json, "cites a repository this run was not given")).isEqualTo("HYPOTHESIS");
        assertThat(label(json, "traverses out of the repository")).isEqualTo("HYPOTHESIS");
        assertThat(label(json, "an honest guess")).isEqualTo("HYPOTHESIS");
        assertThat(label(json, "an honest gap")).isEqualTo("UNKNOWN");

        // nothing downgraded is silent: the original claim and the reason are kept
        assertThat(JsonPath.<List<String>>read(json, "$[?(@.text=='claims a file that does not exist')].requestedLabel")).containsExactly("VERIFIED");
        assertThat(JsonPath.<List<String>>read(json, "$[?(@.text=='claims a file that does not exist')].downgradeReason").getFirst()).contains("could be verified");
        assertThat(JsonPath.<List<String>>read(json, "$[?(@.text=='cites a repository this run was not given')].evidence[0].problem")).containsExactly("Repository is not part of this run");
        assertThat(JsonPath.<List<String>>read(json, "$[?(@.text=='cites a secret file')].evidence[0].problem").getFirst()).contains("restricted");
        assertThat(json).doesNotContain("super-secret-password");
        // the only fully VERIFIED claims are the honest one
        assertThat(JsonPath.<List<String>>read(json, "$[?(@.label=='VERIFIED')].text")).containsExactly("honest: real lines");
    }

    @Test
    void anAgentThatProposesEditingARepositoryOutsideTheRunIsStoppedAndNothingIsStored() throws Exception {
        otherRepoInProject = UUID.fromString(webId);
        String run = Api(createRunOnlyBackend("FIX_AND_PATCH"));
        awaitStatus(run, "AWAITING_APPROVAL");
        decide(u("approver"), pendingApprovalId(run), "APPROVE", pendingApprovalHash(run)).andExpect(status().isOk());
        awaitStatus(run, "FAILED");
        api.get(u("owner"), "/api/v1/runs/" + run).andExpect(jsonPath("$.failure.code").value("CHANGE_OUTSIDE_SCOPE")).andExpect(jsonPath("$.failure.retryable").value(false));
        api.get(u("owner"), "/api/v1/runs/" + run + "/changes").andExpect(jsonPath("$.length()").value(0));
    }

    private String createRunOnlyBackend() throws Exception { return createRunOnlyBackend("INVESTIGATE_ONLY"); }

    private String createRunOnlyBackend(String mode) throws Exception {
        return com.jayway.jsonpath.JsonPath.read(rawCreate(u("owner"), body(mode, TICKET_TEXT, backendId)).andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString(), "$.id");
    }

    private static String Api(String id) { return id; }

    private static String label(String json, String text) { return JsonPath.<List<String>>read(json, "$[?(@.text=='" + text + "')].label").getFirst(); }
}
