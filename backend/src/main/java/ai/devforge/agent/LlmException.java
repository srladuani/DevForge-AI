package ai.devforge.agent;

/** Provider-neutral failure taxonomy. AUTH_INVALID and QUOTA_EXHAUSTED are never retried automatically. */
public class LlmException extends RuntimeException {
    public enum Kind {
        AUTH_INVALID(false), QUOTA_EXHAUSTED(false), RATE_LIMITED(true), TIMEOUT(true), UNAVAILABLE(true), BAD_REQUEST(false), CONTENT_FILTERED(false);

        private final boolean retryable;
        Kind(boolean retryable) { this.retryable = retryable; }
        public boolean retryable() { return retryable; }
    }

    private final Kind kind;

    public LlmException(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public Kind kind() { return kind; }
    public boolean retryable() { return kind.retryable(); }
}
