package ai.devforge.integration.internal;

import ai.devforge.integration.GitHostException;
import ai.devforge.integration.GitHostException.Kind;
import ai.devforge.integration.GitHosts;
import ai.devforge.integration.HostCredential;
import ai.devforge.integration.PullRequestRef;
import ai.devforge.integration.RemoteRepo;
import java.util.List;
import java.util.Optional;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/** The {@link GitHosts} the rest of the platform uses: it picks the adapter for a host type, or for the host a checkout's remote points to. */
@Component
@Primary
class GitHostRegistry implements GitHosts {
    private final List<HostAdapter> adapters;

    GitHostRegistry(List<HostAdapter> adapters) { this.adapters = adapters; }

    private HostAdapter adapter(String hostType) {
        return adapters.stream().filter(a -> a.supports(hostType)).findFirst().orElseThrow(() -> new GitHostException(Kind.UNSUPPORTED_REMOTE, "Publishing to " + hostType + " is not supported"));
    }

    @Override public boolean supports(String hostType) { return adapters.stream().anyMatch(a -> a.supports(hostType)); }

    @Override
    public RemoteRepo detect(String remoteUrl) {
        GitHostException last = null;
        for (HostAdapter a : adapters) {
            try {
                return a.detect(remoteUrl);
            } catch (GitHostException e) {
                if (e.kind() != Kind.UNSUPPORTED_REMOTE) throw e;
                last = e;
            }
        }
        if (last == null) throw new GitHostException(Kind.UNSUPPORTED_REMOTE, "No Git host is configured");
        String where = remoteUrl == null ? "" : remoteUrl.replaceAll("^[a-z+]+://(?:[^/@]+@)?", "").replaceAll("^[^@/]+@", "").split("[/:]", 2)[0];
        throw new GitHostException(Kind.UNSUPPORTED_REMOTE, "This repository's remote (" + where + ") is not on a configured Git host (" + String.join(", ", adapters.stream().map(HostAdapter::gitHostName).toList())
            + "). Credentials are never sent anywhere else.");
    }

    @Override public RemoteRepo resolve(String hostType, String remoteUrl) { return adapter(hostType).resolve(hostType, remoteUrl); }

    @Override public String gitAuthHeader(String hostType, HostCredential c) { return adapter(hostType).gitAuthHeader(hostType, c); }

    @Override public String defaultBranch(String hostType, HostCredential c, RemoteRepo repo) { return adapter(hostType).defaultBranch(hostType, c, repo); }

    @Override
    public PullRequestRef openPullRequest(String hostType, HostCredential c, RemoteRepo repo, String head, String base, String title, String body, boolean draft) {
        return adapter(hostType).openPullRequest(hostType, c, repo, head, base, title, body, draft);
    }

    @Override public Optional<PullRequestRef> findOpenPullRequest(String hostType, HostCredential c, RemoteRepo repo, String head) { return adapter(hostType).findOpenPullRequest(hostType, c, repo, head); }
}
