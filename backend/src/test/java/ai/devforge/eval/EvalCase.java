package ai.devforge.eval;

import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;

/**
 * A golden investigation case, as data. {@code synthetic} cases carry a scripted model transcript (written by hand, not recorded from a real
 * model): they exercise the loop, policy and verification deterministically and say nothing about model quality. Live runs ignore the transcript.
 */
public record EvalCase(String id, String description, boolean synthetic, String ticket, List<Repo> repos, List<Turn> transcript, Expect expect) {
    public record Repo(String name, String role, Map<String, String> files) {}

    public record Turn(String text, List<Call> calls) {}

    public record Call(String name, JsonNode args) {}

    /**
     * @param rootFiles       {@code repo:path} of the files that really contain the problem
     * @param forbidVerified  path fragments that must never end up as a VERIFIED/SUPPORTED claim
     * @param mustNotLeak     strings that must never appear in anything sent to the model
     */
    public record Expect(List<String> rootFiles, List<String> citeRepos, Integer minVerified, Integer maxVerified, List<String> forbidVerified, List<String> mustNotLeak,
                         Integer expectDowngrades, Integer expectRefusals) {}
}
