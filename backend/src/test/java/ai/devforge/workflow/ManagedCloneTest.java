package ai.devforge.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.devforge.testsupport.Api;
import ai.devforge.testsupport.FixtureRepos;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

/**
 * Repositories DevForge clones itself. The "Git host" is a bare repository on disk addressed like a Bitbucket remote
 * ({@code file:///…/bitbucket/acme/service.git}); the clone, fetch, checkout, access rules and credential handling are the production code.
 */
@TestPropertySource(properties = {"devforge.bitbucket.allow-file-remotes=true", "devforge.workflow.queue-name=managed-clone"})
class ManagedCloneTest extends WorkflowTestBase {
    private static final String SECRET = "bb-token-THIS-MUST-NEVER-REACH-THE-CLONE";
    private static final Path MANAGED_ROOT = makeRoot();

    private static Path makeRoot() {
        try {
            return Files.createTempDirectory("devforge-managed-");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void managedRoot(DynamicPropertyRegistry registry) {
        registry.add("devforge.repositories.managed-root", MANAGED_ROOT::toString);
    }

    @org.junit.jupiter.api.AfterEach
    void forgetClones() throws IOException {
        try (var walk = Files.walk(MANAGED_ROOT)) {
            walk.sorted(java.util.Comparator.reverseOrder()).filter(p -> !p.equals(MANAGED_ROOT)).forEach(p -> p.toFile().delete());
        }
    }

    private Path seed;
    private Path bare;
    private String url;

    private void remote() {
        seed = FixtureRepos.gradleSpring(tmp.resolve("seed"));
        bare = tmp.resolve("bitbucket").resolve("acme").resolve("service.git");
        FixtureRepos.git(tmp, "clone", "--bare", "-q", seed.toString(), bare.toString());
        url = bare.toUri().toString().replaceAll("/$", "");
    }

    private String branchOfSeed() { return FixtureRepos.git(seed, "rev-parse", "--abbrev-ref", "HEAD").strip(); }

    private String pushNewCommitTo(String branch, String file) throws IOException {
        Files.writeString(seed.resolve(file), "change\n");
        FixtureRepos.git(seed, "add", "-A");
        FixtureRepos.git(seed, "commit", "-q", "-m", "change " + file);
        FixtureRepos.git(seed, "push", "-q", bare.toString(), "HEAD:refs/heads/" + branch);
        return FixtureRepos.git(seed, "rev-parse", "HEAD").strip();
    }

    private void connection(String who, String scope, String secret) throws Exception {
        String scopeJson = scope == null ? "" : ",\"scope\":\"" + scope + "\"";
        String id = Api.json(api.post(u(who), "/api/v1/me/connections", "{\"kind\":\"GIT_HOST\",\"providerType\":\"BITBUCKET_CLOUD\",\"authMethod\":\"PAT\",\"label\":\"bb\",\"secret\":\"" + secret + "\"" + scopeJson + "}").andExpect(status().isCreated()), "$.id");
        jdbc.sql("update connection set status = 'VALID', last_verified_at = now() where id = ?").param(UUID.fromString(id)).update();
    }

    private String add(String who, String project, String cloneUrl) throws Exception {
        return Api.json(api.post(u(who), "/api/v1/workspaces/" + project + "/repositories", "{\"cloneUrl\":\"" + cloneUrl + "\",\"role\":\"BACKEND\"}").andExpect(status().isCreated()), "$.repositoryId");
    }

    private String eventsOf(String run) throws Exception { return api.get(u("owner"), "/api/v1/runs/" + run + "/events").andReturn().getResponse().getContentAsString(); }

    @Test
    void aRepositoryAddedByUrlIsClonedWithTheUsersOwnTokenAndNeverKeepsTheToken() throws Exception {
        remote();
        connection("owner", "acme/service", SECRET);
        api.post(u("owner"), "/api/v1/workspaces/" + ws + "/repositories", "{\"cloneUrl\":\"" + url + "\",\"role\":\"BACKEND\",\"name\":\"service\"}")
            .andExpect(status().isCreated()).andExpect(jsonPath("$.managed").value(true)).andExpect(jsonPath("$.remoteUrl").value(url)).andExpect(jsonPath("$.name").value("service"))
            .andExpect(jsonPath("$.headSha").isNotEmpty()).andExpect(jsonPath("$.profile.buildTools[0].tool").value("gradle"));
        Path clone = Files.list(MANAGED_ROOT).filter(p -> p.getFileName().toString().endsWith("-service")).findFirst().orElseThrow();
        assertThat(Files.readString(clone.resolve(".git/config"))).doesNotContain(SECRET).contains(url);
        assertThat(FixtureRepos.git(clone, "rev-parse", "HEAD")).isEqualTo(FixtureRepos.git(seed, "rev-parse", "HEAD"));
        assertThat(jdbc.sql("select coalesce(string_agg(coalesce(details,''), ' '), '') from audit_event").query(String.class).single()).doesNotContain(SECRET);
    }

    @Test
    void aSecondProjectReusesTheSameCloneButOnlyForSomeoneWhoCanReadTheRepository() throws Exception {
        remote();
        connection("owner", "acme/service", SECRET);
        String first = add("owner", ws, url);

        String other = Api.json(api.post(u("operator"), "/api/v1/workspaces", "{\"name\":\"other team\",\"kind\":\"SUPPORT\"}").andExpect(status().isCreated()), "$.id");
        // no connection at all, or one limited to a different repository: refused, and nothing about the existing clone is revealed
        api.post(u("operator"), "/api/v1/workspaces/" + other + "/repositories", "{\"cloneUrl\":\"" + url + "\",\"role\":\"BACKEND\"}")
            .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("NO_USABLE_CONNECTION")).andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("acme/service")));
        connection("operator", "acme/other-repo", "another-token");
        api.post(u("operator"), "/api/v1/workspaces/" + other + "/repositories", "{\"cloneUrl\":\"" + url + "\",\"role\":\"BACKEND\"}").andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("NO_USABLE_CONNECTION"));

        connection("operator", "acme/*", "an-owner-wide-token");
        assertThat(add("operator", other, url)).as("one clone per remote, shared").isEqualTo(first);
        assertThat(Files.list(MANAGED_ROOT).filter(p -> p.getFileName().toString().endsWith("-service")).count()).isEqualTo(1);
    }

    @Test
    void addressesThatCouldLeakACredentialOrReachAnUnconfiguredHostAreRefused() throws Exception {
        connection("owner", null, SECRET);
        api.post(u("owner"), "/api/v1/workspaces/" + ws + "/repositories", "{\"cloneUrl\":\"https://someone:" + SECRET + "@bitbucket.org/acme/service.git\",\"role\":\"BACKEND\"}")
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("CLONE_URL_HAS_CREDENTIALS")).andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(SECRET))));
        api.post(u("owner"), "/api/v1/workspaces/" + ws + "/repositories", "{\"cloneUrl\":\"https://example.com/acme/service.git\",\"role\":\"BACKEND\"}")
            .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("CLONE_URL_UNSUPPORTED"));
        api.post(u("owner"), "/api/v1/workspaces/" + ws + "/repositories", "{\"cloneUrl\":\"" + url() + "\",\"path\":\"" + tmp + "\",\"role\":\"BACKEND\"}")
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("PATH_OR_URL"));
        assertThat(MANAGED_ROOT).isEmptyDirectory();
    }

    private String url() { return "https://bitbucket.org/acme/service.git"; }

    @Test
    void aRepositoryThatDoesNotExistOrCannotBeReadFailsClearlyAndLeavesNoHalfMadeClone() throws Exception {
        connection("owner", null, SECRET);
        String missing = tmp.resolve("bitbucket").resolve("acme").resolve("nothing.git").toUri().toString().replaceAll("/$", "");
        api.post(u("owner"), "/api/v1/workspaces/" + ws + "/repositories", "{\"cloneUrl\":\"" + missing + "\",\"role\":\"BACKEND\"}")
            .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("HOST_NOT_FOUND"));
        try (var list = Files.list(MANAGED_ROOT)) {
            assertThat(list.filter(p -> p.getFileName().toString().endsWith("-nothing")).toList()).isEmpty();
        }
    }

    @Test
    void refreshingBringsInNewCommitsFromTheTargetBranchAndSaysWhenItCannot() throws Exception {
        remote();
        connection("owner", "acme/service", SECRET);
        String repo = add("owner", ws, url);
        String before = Api.json(api.get(u("owner"), "/api/v1/workspaces/" + ws + "/repositories"), "$[?(@.repositoryId=='" + repo + "')].headSha[0]");

        String newest = pushNewCommitTo(branchOfSeed(), "NEW.md");
        api.post(u("owner"), "/api/v1/workspaces/" + ws + "/repositories/" + repo + "/refresh", null).andExpect(status().isOk()).andExpect(jsonPath("$.headSha").value(newest));
        assertThat(newest).isNotEqualTo(before);

        // the code a fix is based on is the branch the pull request will go into
        FixtureRepos.git(seed, "checkout", "-q", "-b", "develop");
        String onDevelop = pushNewCommitTo("develop", "DEVELOP.md");
        api.patch(u("owner"), "/api/v1/workspaces/" + ws + "/repositories/" + repo, "{\"targetBranch\":\"develop\"}").andExpect(status().isOk());
        api.post(u("owner"), "/api/v1/workspaces/" + ws + "/repositories/" + repo + "/refresh", null).andExpect(status().isOk()).andExpect(jsonPath("$.headSha").value(onDevelop)).andExpect(jsonPath("$.defaultBranch").value("develop"));

        api.patch(u("owner"), "/api/v1/workspaces/" + ws + "/repositories/" + repo, "{\"targetBranch\":\"no-such-branch\"}").andExpect(status().isOk());
        api.post(u("owner"), "/api/v1/workspaces/" + ws + "/repositories/" + repo + "/refresh", null).andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("REFRESH_FAILED"))
            .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("no-such-branch")));
    }

    @Test
    void aRunStartsOnTheLatestCodeOfTheTargetBranchOrRecordsThatItCouldNotRefresh() throws Exception {
        remote();
        connection("owner", "acme/service", SECRET);
        String repo = add("owner", ws, url);
        String newest = pushNewCommitTo(branchOfSeed(), "FRESH.md");

        String run = Api.json(rawCreate(u("owner"), body("INVESTIGATE_ONLY", TICKET_TEXT, repo)).andExpect(status().isAccepted()), "$.id");
        assertThat(Api.json(api.get(u("owner"), "/api/v1/runs/" + run), "$.repositories[0].baseSha")).isEqualTo(newest);
        assertThat(eventsOf(run)).doesNotContain("REPOSITORY_NOT_REFRESHED");

        // a teammate with no connection of their own can still run on what was last fetched, and the run says so
        String stale = pushNewCommitTo(branchOfSeed(), "STALE.md");
        String second = Api.json(rawCreate(u("operator"), body("INVESTIGATE_ONLY", TICKET_TEXT, repo)).andExpect(status().isAccepted()), "$.id");
        assertThat(Api.json(api.get(u("operator"), "/api/v1/runs/" + second), "$.repositories[0].baseSha")).isEqualTo(newest).isNotEqualTo(stale);
        assertThat(eventsOf(second)).contains("REPOSITORY_NOT_REFRESHED").contains("you have no verified Git connection that covers acme/service").doesNotContain(SECRET);
    }
}
