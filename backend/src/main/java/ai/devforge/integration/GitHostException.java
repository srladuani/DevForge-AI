package ai.devforge.integration;

/** Provider-neutral Git host failure; the workflow decides what each kind means for a publication. */
public class GitHostException extends RuntimeException {
    public enum Kind {
        /** The credential was rejected, or the account may not do this. */
        AUTH_INVALID,
        /** The repository (or pull request) does not exist or is not visible to this account. */
        NOT_FOUND,
        UNAVAILABLE,
        /** The host refused the request on its merits (for example, nothing to compare). */
        REJECTED,
        /** The repository's remote does not point at a configured host of this type, so no credential is sent there. */
        UNSUPPORTED_REMOTE
    }

    private final Kind kind;

    public GitHostException(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public Kind kind() { return kind; }
}
