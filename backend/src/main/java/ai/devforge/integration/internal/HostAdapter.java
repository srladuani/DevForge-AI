package ai.devforge.integration.internal;

import ai.devforge.integration.GitHosts;

/** One Git host's implementation of {@link GitHosts}. The registry (the {@code GitHosts} other modules see) routes to these by host type. */
interface HostAdapter extends GitHosts {
    /** The address of the git host this adapter sends credentials to, for messages. */
    String gitHostName();
}
