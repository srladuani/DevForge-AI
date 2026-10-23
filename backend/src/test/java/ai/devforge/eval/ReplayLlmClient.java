package ai.devforge.eval;

import ai.devforge.agent.CredentialContext;
import ai.devforge.agent.LlmClient;
import ai.devforge.agent.LlmRequest;
import ai.devforge.agent.LlmResponse;
import ai.devforge.agent.ToolCall;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Replays a case's scripted model turns in order and records every request, so tests can inspect exactly what the "model" was shown. */
final class ReplayLlmClient implements LlmClient {
    private final List<EvalCase.Turn> turns;
    private final List<LlmRequest> requests = new CopyOnWriteArrayList<>();
    private int next;

    ReplayLlmClient(List<EvalCase.Turn> turns) { this.turns = turns; }

    List<LlmRequest> requests() { return List.copyOf(requests); }

    @Override
    public synchronized LlmResponse complete(LlmRequest request, CredentialContext credential) {
        requests.add(request);
        if (next >= turns.size()) throw new IllegalStateException("The transcript has no turn " + (next + 1) + " but the loop asked for one");
        EvalCase.Turn t = turns.get(next++);
        List<ToolCall> calls = new ArrayList<>();
        int i = 0;
        for (EvalCase.Call c : t.calls() == null ? List.<EvalCase.Call>of() : t.calls()) calls.add(new ToolCall("call_" + next + "_" + (i++), c.name(), c.args().toString()));
        return new LlmResponse(t.text(), calls, new LlmResponse.Usage(500L * next, 80L), calls.isEmpty() ? LlmResponse.FinishReason.END_TURN : LlmResponse.FinishReason.TOOL_CALLS);
    }
}
