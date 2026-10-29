package ai.devforge.repository.internal;

import ai.devforge.platform.DomainException;
import ai.devforge.platform.Hashes;
import ai.devforge.repository.EvidenceRef;
import ai.devforge.repository.EvidenceVerifier;
import ai.devforge.repository.LineHit;
import ai.devforge.repository.RepoReader;
import ai.devforge.repository.SearchHit;
import ai.devforge.repository.VerifiedEvidence;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
class GitRepoReader implements RepoReader, EvidenceVerifier {
    private static final int MAX_READ_LINES = 400;
    private static final int MAX_EXCERPT_LINES = 40;

    private final RepositoryStore store;
    private final GitCli git;
    private final PathPolicy policy;

    GitRepoReader(RepositoryStore store, GitCli git, PathPolicy policy) {
        this.store = store;
        this.git = git;
        this.policy = policy;
    }

    @Override
    public List<String> listFiles(UUID repoId, String sha, String pathPrefix, List<String> extensions) {
        Path root = store.rootOf(repoId);
        String full = git.verifySha(root, sha);
        return git.lsTree(root, full).stream().filter(f -> !policy.isDenied(f))
            .filter(f -> pathPrefix == null || pathPrefix.isEmpty() || f.startsWith(pathPrefix))
            .filter(f -> extensions == null || extensions.isEmpty() || extensions.stream().anyMatch(f::endsWith)).toList();
    }

    @Override
    public List<SearchHit> rankFiles(UUID repoId, String sha, List<String> keywords, String pathPrefix, List<String> extensions, int top) {
        Path root = store.rootOf(repoId);
        String full = git.verifySha(root, sha);
        List<String> universe = listFiles(repoId, full, pathPrefix, extensions);
        if (universe.isEmpty()) return List.of();
        Map<String, Double> score = new HashMap<>();
        Map<String, Integer> matched = new HashMap<>();
        for (String kw : keywords.stream().distinct().limit(12).toList()) {
            if (!kw.matches("[A-Za-z0-9_.-]{3,64}")) continue;
            List<String> hits = grepFiles(root, full, kw, pathPrefix).stream().filter(universe::contains).toList();
            // A term in >30% of files carries no signal, but the statistic is meaningless in tiny repositories.
            if (hits.isEmpty() || (universe.size() >= 30 && hits.size() > universe.size() * 0.3)) continue;
            double idf = Math.log((double) universe.size() / hits.size());
            for (String f : hits) {
                score.merge(f, idf, Double::sum);
                matched.merge(f, 1, Integer::sum);
            }
        }
        for (String f : List.copyOf(score.keySet())) {
            String base = f.substring(f.lastIndexOf('/') + 1).toLowerCase();
            for (String kw : keywords) if (kw.length() >= 3 && base.contains(kw.toLowerCase())) score.merge(f, 3.0, Double::sum);
            // Tests mention the same words as the code under test but are rarely where a bug lives: rank production code first.
            if (isTestPath(f)) score.computeIfPresent(f, (k, v) -> v * TEST_PATH_FACTOR);
        }
        return score.entrySet().stream().sorted((a, b) -> Double.compare(b.getValue(), a.getValue())).limit(Math.max(1, Math.min(top, 100)))
            .map(e -> new SearchHit(e.getKey(), e.getValue(), matched.getOrDefault(e.getKey(), 0))).toList();
    }

    private static final double TEST_PATH_FACTOR = 0.4;

    static boolean isTestPath(String path) {
        String p = "/" + path.toLowerCase();
        return p.contains("/test/") || p.contains("/tests/") || p.contains("/__tests__/") || p.contains("/integrationtest/") || p.matches(".*\\.(test|spec)\\.[a-z]+$")
            || p.matches(".*/[^/]*(test|tests|spec)\\.(java|kt|groovy|js|jsx|ts|tsx|py)$");
    }

    private List<String> grepFiles(Path root, String sha, String term, String prefix) {
        String out = git.run(root, "grep", "-l", "-i", "-w", "-F", "-I", "--no-color", "-e", term, sha, "--", prefix == null || prefix.isEmpty() ? "." : prefix).text();
        List<String> files = new ArrayList<>();
        for (String line : out.split("\n")) {
            if (line.length() > sha.length() + 1 && line.startsWith(sha + ":")) files.add(line.substring(sha.length() + 1));
        }
        return files;
    }

    @Override
    public List<LineHit> grepLines(UUID repoId, String sha, String term, String pathPrefix, int maxHits) {
        if (term == null || !term.matches("[A-Za-z0-9_.\\- ]{2,80}")) throw DomainException.badRequest("INVALID_SEARCH_TERM", "Search term has unsupported characters");
        Path root = store.rootOf(repoId);
        String full = git.verifySha(root, sha);
        String out = git.run(root, "grep", "-n", "-i", "-F", "-I", "--no-color", "-e", term, full, "--", pathPrefix == null || pathPrefix.isEmpty() ? "." : pathPrefix).text();
        List<LineHit> hits = new ArrayList<>();
        for (String line : out.split("\n")) {
            if (hits.size() >= Math.max(1, Math.min(maxHits, 200))) break;
            if (!line.startsWith(full + ":")) continue;
            String rest = line.substring(full.length() + 1);
            int c1 = rest.indexOf(':');
            int c2 = c1 < 0 ? -1 : rest.indexOf(':', c1 + 1);
            if (c2 < 0) continue;
            String path = rest.substring(0, c1);
            if (policy.isDenied(path)) continue;
            try {
                hits.add(new LineHit(path, Integer.parseInt(rest.substring(c1 + 1, c2)), rest.substring(c2 + 1).strip()));
            } catch (NumberFormatException ignored) {
                // not a match line
            }
        }
        return hits;
    }

    @Override
    public String read(UUID repoId, String sha, String path, int startLine, int endLine) {
        Path root = store.rootOf(repoId);
        String full = git.verifySha(root, sha);
        String rel = policy.normalizeRelative(path);
        if (policy.isDenied(rel)) throw DomainException.forbidden("PATH_RESTRICTED", "This file is restricted and cannot be read");
        if (startLine < 1 || endLine < startLine || endLine - startLine + 1 > MAX_READ_LINES) {
            throw DomainException.badRequest("INVALID_RANGE", "Line range must be 1-based and at most " + MAX_READ_LINES + " lines");
        }
        String text = git.cat(root, full, rel);
        if (text == null) throw DomainException.notFound("FILE_NOT_FOUND", "File not found at that commit (or it is binary)");
        String[] lines = text.split("\n", -1);
        int count = lineCount(text, lines);
        if (startLine > count) throw DomainException.badRequest("INVALID_RANGE", "Range is past the end of the file (" + count + " lines)");
        int to = Math.min(endLine, count);
        StringBuilder sb = new StringBuilder();
        for (int i = startLine; i <= to; i++) sb.append(i).append(": ").append(lines[i - 1]).append('\n');
        return sb.toString();
    }

    @Override
    public VerifiedEvidence verify(EvidenceRef ref) {
        try {
            Path root = store.rootOf(ref.repositoryId());
            String full = git.verifySha(root, ref.sha());
            String rel = policy.normalizeRelative(ref.path());
            if (policy.isDenied(rel)) return fail(ref, "File is restricted");
            if (ref.startLine() < 1 || ref.endLine() < ref.startLine()) return fail(ref, "Invalid line range");
            String text = git.cat(root, full, rel);
            if (text == null) return fail(ref, "File does not exist at that commit");
            String[] lines = text.split("\n", -1);
            int count = lineCount(text, lines);
            if (ref.endLine() > count) return fail(ref, "Cited lines are past the end of the file (" + count + " lines)");
            StringBuilder range = new StringBuilder();
            for (int i = ref.startLine(); i <= ref.endLine(); i++) range.append(lines[i - 1]).append('\n');
            StringBuilder excerpt = new StringBuilder();
            for (int i = ref.startLine(); i <= Math.min(ref.endLine(), ref.startLine() + MAX_EXCERPT_LINES - 1); i++) excerpt.append(lines[i - 1]).append('\n');
            return new VerifiedEvidence(new EvidenceRef(ref.repositoryId(), full, rel, ref.startLine(), ref.endLine()), true, Hashes.sha256(range.toString()), excerpt.toString(), null);
        } catch (DomainException e) {
            return fail(ref, e.getMessage());
        }
    }

    /** A trailing newline ends the last line; it does not start another one. */
    private static int lineCount(String text, String[] split) {
        return split.length - (text.endsWith("\n") ? 1 : 0);
    }

    private static VerifiedEvidence fail(EvidenceRef ref, String problem) {
        return new VerifiedEvidence(ref, false, null, null, problem);
    }
}
