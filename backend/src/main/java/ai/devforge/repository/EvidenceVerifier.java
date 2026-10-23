package ai.devforge.repository;

public interface EvidenceVerifier {
    VerifiedEvidence verify(EvidenceRef ref);
}
