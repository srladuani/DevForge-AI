package ai.devforge.workflow.internal;

import ai.devforge.workflow.RunStatus;
import ai.devforge.workflow.RunViews.EventView;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Server-sent events for a run, fed from the run_event table (so it works across restarts and multiple instances). A client that
 * reconnects with Last-Event-ID receives exactly the events it missed. The stream ends when the run reaches a final state.
 */
@Component
class RunEventStream {
    private static final long TIMEOUT_MS = 30 * 60 * 1000L;
    private static final long POLL_MS = 400;
    private static final long KEEPALIVE_MS = 15_000;

    private final RunStore store;

    RunEventStream(RunStore store) { this.store = store; }

    /** Authorization must already have been checked: this runs on its own thread without a security context. */
    SseEmitter open(UUID runId, int after) {
        SseEmitter emitter = new SseEmitter(TIMEOUT_MS);
        AtomicBoolean open = new AtomicBoolean(true);
        emitter.onCompletion(() -> open.set(false));
        emitter.onTimeout(() -> open.set(false));
        emitter.onError(e -> open.set(false));
        Thread.ofVirtual().name("sse-" + runId).start(() -> {
            int last = after;
            long lastSend = System.currentTimeMillis();
            try {
                while (open.get()) {
                    List<EventView> batch = store.eventsAfter(runId, last, 200);
                    for (EventView e : batch) {
                        emitter.send(SseEmitter.event().id(String.valueOf(e.seq())).name(e.type()).data(store.json.writeValueAsString(e)));
                        last = e.seq();
                        lastSend = System.currentTimeMillis();
                    }
                    if (batch.isEmpty()) {
                        RunStatus status = store.find(runId).map(RunRow::status).orElse(RunStatus.CANCELLED);
                        if (RunStateMachine.isFinal(status)) {
                            emitter.complete();
                            return;
                        }
                        if (System.currentTimeMillis() - lastSend > KEEPALIVE_MS) {
                            emitter.send(SseEmitter.event().comment("keepalive"));
                            lastSend = System.currentTimeMillis();
                        }
                        Thread.sleep(POLL_MS);
                    }
                }
            } catch (IOException | IllegalStateException e) {
                // client went away
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (RuntimeException e) {
                emitter.completeWithError(e);
            }
        });
        return emitter;
    }
}
