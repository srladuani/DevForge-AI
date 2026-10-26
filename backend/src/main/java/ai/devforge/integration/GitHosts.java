package ai.devforge.integration;

import java.util.Optional;

/**
 * What the platform needs from a Git host to publish a change: identify the repository from a remote, authenticate a push, and open a pull
 * request. Pushing itself is done by git; hosts only supply the identity, the credential header and the review request. Nothing here merges.
 */
public interface GitHosts {
    boolean supports(String hostType);

    /**
     * Finds which configured host a checkout's remote belongs to.
     *
     * @throws GitHostException UNSUPPORTED_REMOTE if no configured host matches; no credential may be sent there
     */
    RemoteRepo detect(String remoteUrl);

    /**
     * @throws GitHostException UNSUPPORTED_REMOTE if the remote is not on a host configured for this type; no credential may be sent there
     */
    RemoteRepo resolve(String hostType, String remoteUrl);

    /** A complete HTTP header line for git's {@code http.extraHeader}. Treat it as a secret. */
    String gitAuthHeader(String hostType, HostCredential credential);

    /** The branch pull requests should target. */
    String defaultBranch(String hostType, HostCredential credential, RemoteRepo repo);

    PullRequestRef openPullRequest(String hostType, HostCredential credential, RemoteRepo repo, String head, String base, String title, String body, boolean draft);

    Optional<PullRequestRef> findOpenPullRequest(String hostType, HostCredential credential, RemoteRepo repo, String head);
}
