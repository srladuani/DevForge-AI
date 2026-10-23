package ai.devforge.identity;

/** Checks a secret against its provider. Implemented by the module that knows the provider. */
public interface ConnectionVerifier {
    boolean supports(ConnectionKind kind, String providerType);

    Result verify(String secret);

    /** Same check, told which connection it is for (lets a verifier remember what the credential can do). */
    default Result verify(java.util.UUID connectionId, String secret) { return verify(secret); }

    record Result(ConnectionStatus status, String message) {}
}
