package ai.devforge.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Deterministic {@link LlmClient} for tests and the no-key demo: replays a script of responses or failures, and records requests.
 * It never contacts a network and never reads the credential's secret.
 */
public final class ScriptedLlmClient implements LlmClient {
    private final List<Object> script = new ArrayList<>();
    private final List<LlmRequest> requests = new CopyOnWriteArrayList<>();
    private int next;

    public ScriptedLlmClient thenReturn(LlmResponse response) { script.add(response); return this; }
    public ScriptedLlmClient thenFail(LlmException failure) { script.add(failure); return this; }
    public List<LlmRequest> requests() { return List.copyOf(requests); }

    @Override
    public synchronized LlmResponse complete(LlmRequest request, CredentialContext credential) {
        requests.add(request);
        if (next >= script.size()) throw new IllegalStateException("ScriptedLlmClient script exhausted after " + script.size() + " call(s)");
        Object step = script.get(next++);
        if (step instanceof LlmException e) throw e;
        return (LlmResponse) step;
    }
}
