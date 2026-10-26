package ai.devforge.execution.internal;

import ai.devforge.execution.ProtectedPaths;
import ai.devforge.execution.WorkCopy;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import static ai.devforge.execution.WorkCopy.EditRejected;

/** A scratch copy with a baseline commit, so {@code git diff} is exactly what the run changed. */
final class GitWorkCopy implements WorkCopy {
    static final int MAX_FILE_BYTES = 1_000_000;
    static final int MAX_CHANGED_FILES = 50;
    static final int MAX_PATCH_BYTES = 2_000_000;

    private final Path root;
    private final Path realRoot;

    GitWorkCopy(Path root) throws IOException {
        this.root = root;
        this.realRoot = root.toRealPath();
    }

    @Override public Path root() { return root; }

    @Override
    public String read(String rel) {
        Path p = resolve(rel, false);
        try {
            if (!Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)) throw new EditRejected("NOT_FOUND", "No such file: " + rel);
            if (Files.size(p) > MAX_FILE_BYTES) throw new EditRejected("TOO_LARGE", "File is too large to read");
            return Files.readString(p, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new EditRejected("UNREADABLE", "Could not read " + rel);
        }
    }

    @Override
    public void edit(String rel, String oldText, String newText, Set<String> approved) {
        if (oldText == null || oldText.isEmpty()) throw new EditRejected("EMPTY_MATCH", "The text to replace must not be empty");
        Path p = resolve(rel, true);
        guard(rel, approved);
        String current = read(rel);
        int first = current.indexOf(oldText);
        if (first < 0) throw new EditRejected("NO_MATCH", "The text to replace was not found in " + rel);
        if (current.indexOf(oldText, first + 1) >= 0) throw new EditRejected("AMBIGUOUS_MATCH", "The text to replace occurs more than once in " + rel + "; include more context");
        write(p, current.substring(0, first) + newText + current.substring(first + oldText.length()));
    }

    @Override
    public void createFile(String rel, String content, Set<String> approved) {
        Path p = resolve(rel, true);
        guard(rel, approved);
        if (Files.exists(p, LinkOption.NOFOLLOW_LINKS)) throw new EditRejected("ALREADY_EXISTS", rel + " already exists; edit it instead");
        write(p, content);
    }

    @Override
    public String commit(String message, String authorName, String authorEmail, java.time.Instant when) {
        GitProcess.run(root, "add", "-A");
        String date = when.getEpochSecond() + " +0000";
        var env = java.util.Map.of("GIT_AUTHOR_NAME", authorName, "GIT_AUTHOR_EMAIL", authorEmail, "GIT_COMMITTER_NAME", authorName, "GIT_COMMITTER_EMAIL", authorEmail,
            "GIT_AUTHOR_DATE", date, "GIT_COMMITTER_DATE", date);
        var r = GitProcess.runWithInput(root, message, env, "commit", "-q", "--no-verify", "-F", "-");
        if (r.exit() != 0) throw new EditRejected("COMMIT_FAILED", "Could not record the change as a commit");
        return GitProcess.run(root, "rev-parse", "HEAD").stdout().trim();
    }

    @Override
    public void push(String remoteUrl, String branch, String authHeader) {
        if (!branch.matches("[A-Za-z0-9._/-]{1,200}") || branch.contains("..") || branch.startsWith("-") || branch.endsWith("/") || branch.endsWith(".lock")) throw new PushRejected("REJECTED", "Invalid branch name");
        var env = new java.util.HashMap<String, String>();
        env.put("GIT_TERMINAL_PROMPT", "0");
        env.put("GIT_ASKPASS", "/bin/false");
        if (authHeader != null) {
            env.put("GIT_CONFIG_COUNT", "1");
            env.put("GIT_CONFIG_KEY_0", "http.extraHeader");
            env.put("GIT_CONFIG_VALUE_0", authHeader);
        }
        var r = GitProcess.runWithInput(root, null, env, "push", "--no-verify", remoteUrl, "HEAD:refs/heads/" + branch);
        if (r.exit() == 0) return;
        String out = r.stdout().toLowerCase(java.util.Locale.ROOT);
        // history problems first: they are specific, and text such as a temporary directory name can contain any digits
        if (out.contains("[rejected]") || out.contains("non-fast-forward") || out.contains("fetch first") || out.contains("[remote rejected]") && out.contains("protected")) {
            throw new PushRejected("REJECTED", "The branch already exists on the remote with different history");
        }
        if (java.util.regex.Pattern.compile("authentication failed|could not read username|permission denied|access denied|returned error: 40[13]|http/[0-9.]+ 40[13]|the requested url returned error: 40[13]").matcher(out).find()) {
            throw new PushRejected("AUTH", "The Git host refused the credential or the account may not push to this repository");
        }
        throw new PushRejected("UNREACHABLE", "The push did not complete");
    }

    @Override
    public void applyPatch(String patch, Set<String> approved) {
        if (patch == null || patch.isBlank()) return;
        var names = GitProcess.runWithInput(root, patch, "apply", "--numstat", "-z", "-");
        if (names.exit() != 0) throw new EditRejected("PATCH_INVALID", "The stored patch is not a valid diff");
        for (String part : names.stdout().split("\0")) {
            int tab = part.lastIndexOf('\t');
            String file = tab >= 0 ? part.substring(tab + 1) : part;
            if (file.isBlank()) continue;
            resolve(file, true);
            guard(file, approved);
        }
        if (GitProcess.runWithInput(root, patch, "apply", "--check", "-").exit() != 0) throw new EditRejected("PATCH_DOES_NOT_APPLY", "The stored patch does not apply to this commit");
        if (GitProcess.runWithInput(root, patch, "apply", "-").exit() != 0) throw new EditRejected("PATCH_DOES_NOT_APPLY", "The stored patch could not be applied");
    }

    @Override
    public Diff diff() {
        GitProcess.run(root, "add", "-A");
        List<String> files = GitProcess.run(root, "diff", "--cached", "--name-only").stdout().lines().filter(s -> !s.isBlank()).toList();
        if (files.size() > MAX_CHANGED_FILES) throw new EditRejected("TOO_MANY_FILES", "The change touches more than " + MAX_CHANGED_FILES + " files");
        String patch = GitProcess.run(root, "diff", "--cached", "--no-ext-diff", "--no-textconv", "--no-color", "-U3").stdout();
        if (patch.getBytes(StandardCharsets.UTF_8).length > MAX_PATCH_BYTES) throw new EditRejected("PATCH_TOO_LARGE", "The change is too large to review");
        return new Diff(patch, files);
    }

    @Override
    public void close() { deleteTree(root); }

    private static void guard(String rel, Set<String> approved) {
        if (ProtectedPaths.never(rel)) throw new EditRejected("RESTRICTED_PATH", rel + " is restricted and can never be changed");
        if (ProtectedPaths.needsApproval(rel) && !approved.contains(rel)) {
            throw new EditRejected("PROTECTED_PATH", rel + " is build or CI configuration; it can only change if the approved proposal names it");
        }
    }

    /** Relative, no traversal, no symlink anywhere on the way out of the copy. */
    private Path resolve(String rel, boolean forWrite) {
        if (rel == null || rel.isBlank() || rel.indexOf('\0') >= 0 || rel.startsWith("/") || rel.contains("\\")) throw new EditRejected("INVALID_PATH", "Invalid path");
        for (String seg : rel.split("/")) if (seg.isEmpty() || seg.equals(".") || seg.equals("..")) throw new EditRejected("INVALID_PATH", "Invalid path");
        if (ProtectedPaths.never(rel)) throw new EditRejected("RESTRICTED_PATH", rel + " is restricted");
        Path p = root.resolve(rel).normalize();
        if (!p.startsWith(root)) throw new EditRejected("INVALID_PATH", "Invalid path");
        Path cursor = root;
        for (String seg : rel.split("/")) {
            cursor = cursor.resolve(seg);
            if (Files.isSymbolicLink(cursor)) throw new EditRejected("INVALID_PATH", "Paths through symbolic links are not allowed");
            if (!Files.exists(cursor, LinkOption.NOFOLLOW_LINKS)) break;
        }
        return p;
    }

    private void write(Path p, String content) {
        if (content == null || content.indexOf('\0') >= 0) throw new EditRejected("BINARY_CONTENT", "Only text files can be written");
        if (content.getBytes(StandardCharsets.UTF_8).length > MAX_FILE_BYTES) throw new EditRejected("TOO_LARGE", "The file would be too large");
        try {
            Files.createDirectories(p.getParent());
            if (!p.getParent().toRealPath().startsWith(realRoot)) throw new EditRejected("INVALID_PATH", "Invalid path");
            Path tmp = Files.createTempFile(p.getParent(), ".devforge-", ".tmp");
            Files.writeString(tmp, content, StandardCharsets.UTF_8);
            Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new EditRejected("WRITE_FAILED", "Could not write the file");
        }
    }

    static void deleteTree(Path dir) {
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // best effort: a leftover scratch file is swept by the next cleanup
                }
            });
        } catch (IOException ignored) {
            // already gone
        }
    }
}
