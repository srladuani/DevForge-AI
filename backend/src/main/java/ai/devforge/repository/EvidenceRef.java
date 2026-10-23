package ai.devforge.repository;

import java.util.UUID;

/** A claim about code: this range of this file, at this commit. */
public record EvidenceRef(UUID repositoryId, String sha, String path, int startLine, int endLine) {}
