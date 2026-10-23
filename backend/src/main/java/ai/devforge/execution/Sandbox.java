package ai.devforge.execution;

/** Runs a command with no access to the host beyond the spec's work directory. Never throws for a failing command; that is a result. */
public interface Sandbox {
    /** @throws SandboxUnavailableException if isolation cannot be provided; code is never run unisolated as a fallback */
    SandboxResult run(SandboxSpec spec);

    boolean available();

    String backend();
}
