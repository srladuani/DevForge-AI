package ai.devforge.identity;

/** Checks a secret against its provider. Implemented by the module that knows the provider. */
public interface ConnectionVerifier {
    boolean supports(ConnectionKind kind, String providerType);

    Result verify(String secret);

    /** Same check, told which connection it is for (lets a verifier remember what the credential can do). */
    default Result verify(java.util.UUID connectionId, String secret) { return verify(secret); }

    /** Same, also told whose connection it is (an OAuth verifier may refresh the credential and has to store the new one for its owner). */
    default Result verify(java.util.UUID connectionId, java.util.UUID ownerUserId, String secret) { return verify(connectionId, secret); }

    record Result(ConnectionStatus status, String message) {}
}
