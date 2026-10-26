package ai.devforge.workflow.internal;

import ai.devforge.integration.Ticket;
import ai.devforge.integration.TrackerException;
import ai.devforge.workflow.RunStatus;
import ai.devforge.workflow.RunViews.InputView;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/** Normalises the ticket/prompt, flags suspicious text, and asks for clarification when something blocking is missing. No model involved. */
@Component
class InputRetrievalHandler implements StepHandler {
    private final RunDataStore data;
    private final RunStore store;
    private final InputNormalizer normalizer;
    private final TicketFetcher tickets;
    private final WorkflowProperties props;

    InputRetrievalHandler(RunDataStore data, RunStore store, InputNormalizer normalizer, TicketFetcher tickets, WorkflowProperties props) {
        this.data = data;
        this.store = store;
        this.normalizer = normalizer;
        this.tickets = tickets;
        this.props = props;
    }

    @Override public RunStatus step() { return RunStatus.INPUT_RETRIEVAL; }

    @Override
    @SuppressWarnings("unchecked")
    public Outcome handle(RunRow run, StepContext ctx) {
        List<InputView> inputs = data.inputs(run.id()).stream().filter(i -> !i.kind().equals("REVIEW_FEEDBACK")).toList();
        InputView primary = inputs.getFirst();
        var use = store.trackerUse(run.id());
        // A ticket created without text is read from the tracker with the owner's own connection, unless the owner has already pasted it.
        if (use.isPresent() && primary.kind().equals("TICKET") && primary.text().isBlank() && inputs.stream().noneMatch(i -> i.kind().equals("CLARIFICATION"))) {
            Outcome failed = fetchTicket(run, primary, use.get(), ctx);
            if (failed != null) return failed;
            inputs = data.inputs(run.id()).stream().filter(i -> !i.kind().equals("REVIEW_FEEDBACK")).toList();
            primary = inputs.getFirst();
        }
        String all = inputs.stream().map(InputView::text).collect(Collectors.joining("\n\n"));
        boolean clarified = inputs.stream().anyMatch(i -> i.kind().equals("CLARIFICATION"));
        boolean simNeedsInfo = run.simulated() && all.contains("[sim:info=needed]");
        Map<String, Object> normalized = normalizer.normalize(primary.kind(), primary.sourceRef(), all, clarified, simNeedsInfo);
        data.setNormalized(primary.id(), normalized);
        store.appendEvent(run.id(), "INPUT_NORMALIZED", Map.of("identifiers", ((List<?>) normalized.get("identifiers")).size(), "keywords", normalized.get("keywords"),
            "gaps", normalized.get("gaps"), "source", normalized.get("source")));
        List<String> flags = (List<String>) normalized.get("injectionFlags");
        if (!flags.isEmpty()) store.appendEvent(run.id(), "INJECTION_FLAGGED", Map.of("flags", flags));
        if (InputNormalizer.hasBlockingGap(normalized)) {
            List<String> questions = ((List<Map<String, Object>>) normalized.get("gaps")).stream().filter(g -> Boolean.TRUE.equals(g.get("blocking"))).map(g -> (String) g.get("text")).toList();
            store.appendEvent(run.id(), "NEEDS_INPUT", Map.of("reason", "CLARIFICATION", "questions", questions));
            return Outcome.await("CLARIFICATION", RunStatus.INPUT_RETRIEVAL, "waiting for clarification");
        }
        return Outcome.next(RunStatus.CONTEXT_GATHERING, "input normalised");
    }

    /** @return null when the ticket text was stored; otherwise the outcome that pauses or fails the run. Never invents ticket content. */
    private Outcome fetchTicket(RunRow run, InputView primary, RunStore.TrackerUse use, StepContext ctx) {
        for (int attempt = 1; ; attempt++) {
            try {
                Ticket t = tickets.fetchForRun(run.workspaceId(), use.provider(), use.connectionId(), run.ownerUserId(), primary.sourceRef());
                data.setInputText(primary.id(), TicketFetcher.render(t));
                String title = primary.sourceRef() + ": " + t.title();
                store.setTitle(run.id(), title.length() > 80 ? title.substring(0, 80) : title);
                store.appendEvent(run.id(), "TICKET_FETCHED", Map.of("key", primary.sourceRef(), "tracker", t.tracker(), "comments", t.comments().size(),
                    "routineCommentsSkipped", t.comments().stream().filter(Ticket.TicketComment::routine).count()));
                return null;
            } catch (TrackerException e) {
                switch (e.kind()) {
                    case AUTH_INVALID -> {
                        store.appendEvent(run.id(), "NEEDS_INPUT", Map.of("reason", "CREDENTIAL", "problem", e.kind().name(), "message", "The tracker rejected your connection (" + e.getMessage() + "); update it under My connections, then resume"));
                        return Outcome.await("CREDENTIAL", RunStatus.INPUT_RETRIEVAL, "tracker credential rejected");
                    }
                    case NOT_FOUND, NOT_CONFIGURED, AMBIGUOUS_SITE -> {
                        String q = "The ticket " + primary.sourceRef() + " could not be read from the tracker (" + e.getMessage() + "). Paste the ticket text to continue.";
                        store.appendEvent(run.id(), "NEEDS_INPUT", Map.of("reason", "CLARIFICATION", "questions", List.of(q)));
                        return Outcome.await("CLARIFICATION", RunStatus.INPUT_RETRIEVAL, "ticket unavailable from tracker");
                    }
                    default -> {
                        if (attempt >= 3 || ctx.cancelRequested().getAsBoolean()) return Outcome.fail("TRACKER_UNAVAILABLE", RunStatus.INPUT_RETRIEVAL, e.getMessage() + " (gave up after " + attempt + " attempts)", true);
                        store.appendEvent(run.id(), "RETRYING", Map.of("step", "INPUT_RETRIEVAL", "attempt", attempt, "reason", e.kind().name()));
                        try { Thread.sleep(props.retryBackoffMillis() * attempt); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); return Outcome.fail("TRACKER_UNAVAILABLE", RunStatus.INPUT_RETRIEVAL, "interrupted", true); }
                    }
                }
            }
        }
    }
}
