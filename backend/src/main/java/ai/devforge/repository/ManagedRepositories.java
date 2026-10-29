package ai.devforge.repository;

import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Repositories DevForge clones and keeps current itself, so the server needs no checkout that someone prepared by hand. Every network call uses
 * the <em>requesting person's own</em> Git-host connection; the credential reaches only the git process that needs it and is never stored in the
 * clone, its remote address, a log or a message.
 */
public interface ManagedRepositories {
    /**
     * Clones {@code cloneUrl} (or reuses the clone another project already made) and registers it. The caller must hold a verified connection
     * that can read the repository; that is proved against the host before anything is stored, so a shared clone never lets someone in.
     *
     * @param branch the branch to check out; null means the host's default branch
     * @throws ai.devforge.platform.DomainException with a code that says what to fix (unsupported address, no usable connection, access refused, not found, host unreachable)
     */
    RepositoryView addByUrl(UUID userId, String cloneUrl, @Nullable String name, @Nullable String branch);

    /**
     * Brings the repository up to date with the remote branch using the caller's own connection. Never throws for access or network problems:
     * when it cannot refresh (no connection that covers the repository, branch missing, host unreachable) it returns the repository as it was
     * fetched last, with {@code refreshed=false} and a {@code note} saying why. A repository registered by path is only re-read.
     *
     * @param branch the branch pull requests will go into, which is the code a fix should be based on; null keeps the clone's current branch
     */
    SyncResult sync(UUID repositoryId, UUID userId, @Nullable String branch);

    record SyncResult(RepositoryView repository, boolean refreshed, @Nullable String note) {}
}
