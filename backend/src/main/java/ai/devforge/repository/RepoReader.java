package ai.devforge.repository;

import java.util.List;
import java.util.UUID;

/**
 * Read-only, SHA-pinned access to a registered repository. Files are read from git objects, never from the working tree, so the
 * result is reproducible and the checkout is never touched. Sensitive paths (keys, .env, prod config) are refused.
 */
public interface RepoReader {
    /** Files at {@code sha} (denylisted paths removed), optionally restricted to a path prefix and extensions. */
    List<String> listFiles(UUID repoId, String sha, String pathPrefix, List<String> extensions);

    /** Ranks files by IDF-weighted keyword hits plus a file-name bonus (the M0-S4 baseline retrieval). */
    List<SearchHit> rankFiles(UUID repoId, String sha, List<String> keywords, String pathPrefix, List<String> extensions, int top);

    /** Matching lines (case-insensitive, fixed-string) for one term; bounded. */
    List<LineHit> grepLines(UUID repoId, String sha, String term, String pathPrefix, int maxHits);

    /** 1-based inclusive line range, at most 400 lines. */
    String read(UUID repoId, String sha, String path, int startLine, int endLine);
}
