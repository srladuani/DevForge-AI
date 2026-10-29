package ai.devforge.repository.internal;

import ai.devforge.platform.DomainException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/** Decides which directories may be registered and which files may ever be read or shown to a model. */
@Component
class PathPolicy {
    /** Sensitive by name: never read, never cited, never sent to a model. */
    private static final List<Pattern> DENY = List.of(
        Pattern.compile("(^|/)\\.env(\\..*)?$"),
        Pattern.compile("(^|/)id_(rsa|dsa|ecdsa|ed25519)(\\.pub)?$"),
        Pattern.compile("\\.(pem|key|p12|pfx|jks|keystore|kdbx|crt)$", Pattern.CASE_INSENSITIVE),
        Pattern.compile("(^|/)application-[^/]*(prod|production)[^/]*\\.(ya?ml|properties)$", Pattern.CASE_INSENSITIVE),
        Pattern.compile("(^|/)(secrets?|credentials?)(/|\\.|$)", Pattern.CASE_INSENSITIVE),
        Pattern.compile("(^|/)\\.(npmrc|pypirc|netrc|git-credentials)$"),
        Pattern.compile("(^|/)\\.git(/|$)"));

    private final List<Path> allowedRoots;

    private final Path managedRoot;
    private final boolean allowLocalPaths;

    PathPolicy(RepositoryProperties props) {
        List<String> configured = props.allowedRoots().isEmpty() ? List.of(System.getProperty("user.home")) : props.allowedRoots();
        this.managedRoot = Paths.get(props.managedRoot().isBlank() ? System.getProperty("user.home") + "/.devforge/repos" : props.managedRoot()).toAbsolutePath().normalize();
        this.allowLocalPaths = props.allowLocalPaths();
        // the clones DevForge makes are always readable by it, wherever the administrator put them
        this.allowedRoots = java.util.stream.Stream.concat(configured.stream().map(PathPolicy::real), java.util.stream.Stream.of(real(managedRoot.toString()))).toList();
    }

    Path managedRoot() { return managedRoot; }

    boolean allowLocalPaths() { return allowLocalPaths; }

    private static Path real(String p) {
        try {
            return Paths.get(p).toRealPath();
        } catch (IOException e) {
            return Paths.get(p).toAbsolutePath().normalize();
        }
    }

    /** Canonical path of a registrable repository root; throws a 4xx DomainException otherwise. */
    Path validateRepoRoot(String raw) {
        if (raw == null || raw.isBlank() || raw.indexOf('\0') >= 0) throw DomainException.badRequest("INVALID_PATH", "Repository path is required");
        Path p;
        try {
            p = Paths.get(raw.trim()).toRealPath();
        } catch (IOException | RuntimeException e) {
            throw DomainException.unprocessable("REPOSITORY_NOT_FOUND", "Path does not exist or is not readable");
        }
        if (!Files.isDirectory(p)) throw DomainException.unprocessable("REPOSITORY_NOT_A_DIRECTORY", "Path is not a directory");
        if (allowedRoots.stream().noneMatch(p::startsWith)) {
            throw DomainException.forbidden("REPOSITORY_PATH_NOT_ALLOWED", "Path is outside the directories this server may read");
        }
        if (!Files.exists(p.resolve(".git"))) throw DomainException.unprocessable("NOT_A_GIT_REPOSITORY", "Path is not the root of a git repository");
        return p;
    }

    /** A repo-relative path with no traversal; throws if it could escape or is empty. */
    String normalizeRelative(String path) {
        if (path == null || path.isBlank() || path.indexOf('\0') >= 0 || path.startsWith("/") || path.contains("\\")) {
            throw DomainException.badRequest("INVALID_PATH", "Invalid repository-relative path");
        }
        for (String seg : path.split("/")) {
            if (seg.equals("..") || seg.equals(".") || seg.isEmpty()) throw DomainException.badRequest("INVALID_PATH", "Invalid repository-relative path");
        }
        return path;
    }

    boolean isDenied(String relPath) {
        return DENY.stream().anyMatch(p -> p.matcher(relPath).find());
    }
}
