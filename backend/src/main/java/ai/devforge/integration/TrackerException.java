package ai.devforge.integration;

/** Provider-neutral tracker failure. The workflow decides what each kind means for a run (pause, ask, retry, fail). */
public class TrackerException extends RuntimeException {
    public enum Kind {
        /** The credential was rejected or has expired. */
        AUTH_INVALID(false),
        /** No such ticket, or this user may not see it. */
        NOT_FOUND(false),
        /** The tracker could not be reached or answered with an error that may pass. */
        UNAVAILABLE(true),
        /** The tracker answered, but not in the shape expected (so nothing can be trusted from it). */
        BAD_RESPONSE(true),
        /** The credential reaches several sites and the project does not say which. */
        AMBIGUOUS_SITE(false),
        /** The project or the user has not set up this tracker. */
        NOT_CONFIGURED(false);

        private final boolean transientFailure;
        Kind(boolean transientFailure) { this.transientFailure = transientFailure; }
        public boolean transientFailure() { return transientFailure; }
    }

    private final Kind kind;

    public TrackerException(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public Kind kind() { return kind; }
}
