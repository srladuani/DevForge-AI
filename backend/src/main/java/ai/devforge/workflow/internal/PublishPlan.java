package ai.devforge.workflow.internal;

import java.util.List;
import java.util.UUID;

/**
 * Exactly what a person approves before anything leaves the platform. It is stored as an artifact, so its hash covers every patch, the branch,
 * the title and body of the pull request, and the commit time; change any of them and the approval no longer applies.
 */
record PublishPlan(String hostType, String branch, String prTitle, String prBody, boolean draft, String commitTime, List<PlanRepo> repos) {
    /**
     * @param hostType   which Git host this repository is on (a project may mix hosts); plans made before this field existed were all GitHub
     * @param baseSource where {@code baseBranch} came from: REPOSITORY (the project's setting for this repository), PROJECT, or HOST_DEFAULT (nothing was configured); null in older plans
     */
    record PlanRepo(UUID changeId, UUID repositoryId, String repoName, String hostType, String owner, String name, String pushUrl, String baseBranch, String patchHash, List<String> files, String commitMessage,
                    @org.jspecify.annotations.Nullable String baseSource) {
        public PlanRepo {
            hostType = hostType == null ? "GITHUB" : hostType;
        }
    }
}
