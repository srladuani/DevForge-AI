package ai.devforge.repository;

/** Result of re-reading the cited range from the repository. {@code verified} is only true if the file and lines really exist at that SHA. */
public record VerifiedEvidence(EvidenceRef ref, boolean verified, String contentHash, String excerpt, String problem) {}
