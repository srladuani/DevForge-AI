package ai.devforge.repository.internal;

import ai.devforge.identity.ConnectionKind;
import ai.devforge.identity.Connections;
import ai.devforge.integration.GitHostException;
import ai.devforge.integration.GitHosts;
import ai.devforge.integration.HostCredential;
import ai.devforge.integration.RemoteRepo;
import ai.devforge.platform.DomainException;
import ai.devforge.repository.ManagedRepositories;
import ai.devforge.repository.RepositoryView;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** Clones repositories from a URL into a directory DevForge owns and keeps them current. See {@link ManagedRepositories}. */
@Service
class ManagedRepositoryService implements ManagedRepositories {
    private static final Pattern BRANCH = Pattern.compile("[A-Za-z0-9._/-]{1,100}");
    private static final Pattern URL_WITH_CREDENTIALS = Pattern.compile("^[a-z]+://[^/@]+@.*", Pattern.CASE_INSENSITIVE);

    private final RepositoryStore store;
    private final PathPolicy policy;
    private final GitTransport git;
    private final GitHosts hosts;
    private final Connections connections;
    private final Map<String, ReentrantLock> locks = new ConcurrentHashMap<>();

    ManagedRepositoryService(RepositoryStore store, PathPolicy policy, GitTransport git, GitHosts hosts, Connections connections) {
        this.store = store;
        this.policy = policy;
        this.git = git;
        this.hosts = hosts;
        this.connections = connections;
    }

    @Override
    public RepositoryView addByUrl(UUID userId, String cloneUrl, @Nullable String name, @Nullable String branch) {
        String url = cloneUrl == null ? "" : cloneUrl.strip();
        if (url.isEmpty()) throw DomainException.badRequest("CLONE_URL_REQUIRED", "A repository URL is required");
        if (URL_WITH_CREDENTIALS.matcher(url).matches()) {
            throw DomainException.badRequest("CLONE_URL_HAS_CREDENTIALS", "Remove the user name or token from the address. DevForge uses your own connection from My connections, and never stores a credential in a repository address");
        }
        String wantedBranch = checkedBranch(branch);
        RemoteRepo remote = detect(url);
        String header = authHeader(userId, remote);

        // Proves this person can read the repository before anything is stored or shared, even if the clone already exists.
        var head = git.lookUpDefaultBranch(remote.pushUrl(), header);
        if (!head.ok()) throw failure(head.outcome(), remote, "read");
        String branchToUse = wantedBranch != null ? wantedBranch : head.stdout();

        Path dir = policy.managedRoot().resolve(key(remote));
        ReentrantLock lock = locks.computeIfAbsent(dir.toString(), k -> new ReentrantLock());
        lock.lock();
        try {
            try {
                Files.createDirectories(policy.managedRoot());
            } catch (IOException e) {
                throw new DomainException("CLONE_FAILED", HttpStatus.INTERNAL_SERVER_ERROR, "DevForge could not create its folder for cloned repositories");
            }
            if (!Files.exists(dir.resolve(".git"))) {
                var cloned = git.clone(remote.pushUrl(), dir, header);
                if (!cloned.ok()) {
                    deleteQuietly(dir); // a half-made clone must not be mistaken for a good one next time
                    throw failure(cloned.outcome(), remote, "clone");
                }
            }
            git.pointOriginAt(dir, remote.pushUrl());
            var updated = git.updateTo(dir, branchToUse, header);
            if (updated.outcome() == GitTransport.Outcome.NOT_FOUND || updated.outcome() == GitTransport.Outcome.FAILED) {
                throw DomainException.unprocessable("BRANCH_NOT_FOUND", "The branch " + branchToUse + " does not exist in " + remote.fullName() + ". Check the branch name, or leave it empty to use the default branch");
            }
            if (!updated.ok()) throw failure(updated.outcome(), remote, "update");
            return store.registerManaged(dir, name != null && !name.isBlank() ? name.strip() : remote.name(), remote.pushUrl());
        } finally {
            lock.unlock();
        }
    }

    @Override
    public SyncResult sync(UUID repositoryId, UUID userId, @Nullable String branch) {
        RepositoryView repo = store.get(repositoryId);
        if (!repo.managed()) return new SyncResult(store.refresh(repositoryId), false, null); // a checkout someone registered is only re-read
        String wantedBranch;
        try {
            wantedBranch = checkedBranch(branch);
        } catch (DomainException e) {
            return new SyncResult(store.refresh(repositoryId), false, e.getMessage());
        }
        RemoteRepo remote;
        String header;
        try {
            remote = detect(repo.remoteUrl());
            header = authHeader(userId, remote);
        } catch (DomainException e) {
            String reason = e.code().equals("NO_USABLE_CONNECTION") ? "you have no verified Git connection that covers " + repo.remoteUrl().replaceAll("^.*?[/:]([^/:]+/[^/]+?)(\\.git)?$", "$1") : e.getMessage();
            return stale(repositoryId, reason);
        }
        String target = wantedBranch != null ? wantedBranch : repo.defaultBranch();
        Path dir = Path.of(repo.localPath());
        ReentrantLock lock = locks.computeIfAbsent(dir.toString(), k -> new ReentrantLock());
        lock.lock();
        try {
            git.pointOriginAt(dir, remote.pushUrl());
            var updated = git.updateTo(dir, target, header);
            if (!updated.ok()) {
                return stale(repositoryId, switch (updated.outcome()) {
                    case AUTH -> "the Git host refused your connection for " + remote.fullName();
                    case NOT_FOUND -> "the branch " + target + " was not found on the remote";
                    case UNREACHABLE -> "the Git host could not be reached";
                    default -> "the repository could not be updated";
                });
            }
            return new SyncResult(store.refresh(repositoryId), true, null);
        } finally {
            lock.unlock();
        }
    }

    private SyncResult stale(UUID repositoryId, String reason) {
        RepositoryView v = store.refresh(repositoryId);
        return new SyncResult(v, false, "Not refreshed: " + reason + ". Using the code as it was last fetched (" + (v.headSha() == null ? "unknown" : v.headSha().substring(0, Math.min(8, v.headSha().length()))) + ").");
    }

    private RemoteRepo detect(String url) {
        try {
            return hosts.detect(url);
        } catch (GitHostException e) {
            throw DomainException.unprocessable("CLONE_URL_UNSUPPORTED", e.getMessage());
        }
    }

    /** The caller's own verified connection that covers this repository, as a git header; never another person's. */
    private String authHeader(UUID userId, RemoteRepo remote) {
        var conn = connections.findUsableForRepository(userId, ConnectionKind.GIT_HOST, remote.hostType(), remote.owner(), remote.name())
            .orElseThrow(() -> DomainException.unprocessable("NO_USABLE_CONNECTION", "Add and verify a " + remote.hostType() + " connection that covers " + remote.fullName() + " under My connections, then try again"));
        try {
            return hosts.gitAuthHeader(remote.hostType(), new HostCredential(conn.id(), userId, () -> connections.reveal(conn.id(), userId), s -> connections.replaceSecret(conn.id(), userId, s)));
        } catch (GitHostException e) {
            throw DomainException.unprocessable("HOST_" + e.kind(), e.getMessage());
        }
    }

    private static DomainException failure(GitTransport.Outcome outcome, RemoteRepo remote, String what) {
        return switch (outcome) {
            case AUTH -> DomainException.unprocessable("HOST_AUTH_INVALID", "The Git host refused your connection when trying to " + what + " " + remote.fullName() + ". Check the token's permissions (it needs to read this repository)");
            case NOT_FOUND -> DomainException.unprocessable("HOST_NOT_FOUND", "The Git host cannot find " + remote.fullName() + ", or your connection cannot see it. Check the address and the token's access");
            case UNREACHABLE -> new DomainException("HOST_UNAVAILABLE", HttpStatus.BAD_GATEWAY, "The Git host could not be reached right now; try again");
            default -> DomainException.unprocessable("CLONE_FAILED", "DevForge could not " + what + " " + remote.fullName() + " (the repository may be empty or too large)");
        };
    }

    private static @Nullable String checkedBranch(@Nullable String branch) {
        if (branch == null || branch.isBlank()) return null;
        String b = branch.strip();
        if (!BRANCH.matcher(b).matches() || b.contains("..") || b.startsWith("-") || b.startsWith("/") || b.endsWith("/") || b.endsWith(".lock")) {
            throw DomainException.badRequest("INVALID_BRANCH", "The target branch is not a valid branch name");
        }
        return b;
    }

    /** One directory per remote, named from a hash so no part of it comes from user input. */
    private static String key(RemoteRepo remote) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest((remote.hostType() + ":" + remote.owner() + "/" + remote.name()).toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d, 0, 10) + "-" + remote.name().replaceAll("[^A-Za-z0-9._-]", "_");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void deleteQuietly(Path dir) {
        try (var walk = Files.walk(dir)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        } catch (IOException ignored) {
            // best effort
        }
    }
}
