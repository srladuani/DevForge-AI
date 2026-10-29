package ai.devforge.workflow.internal;

import ai.devforge.identity.Connections;
import ai.devforge.identity.ConnectionKind;
import ai.devforge.identity.ConnectionView;
import ai.devforge.integration.Ticket;
import ai.devforge.integration.Ticket.TicketComment;
import ai.devforge.integration.TrackerCredential;
import ai.devforge.integration.TrackerException;
import ai.devforge.integration.TrackerException.Kind;
import ai.devforge.integration.Trackers;
import ai.devforge.workspace.TrackerBinding;
import ai.devforge.workspace.Workspaces;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Fetches a ticket with the given user's own tracker connection, using the project's tracker setting. */
@Component
class TicketFetcher {
    private final Workspaces workspaces;
    private final Connections connections;
    private final Trackers trackers;

    TicketFetcher(Workspaces workspaces, Connections connections, Trackers trackers) {
        this.workspaces = workspaces;
        this.connections = connections;
        this.trackers = trackers;
    }

    /** What a fetch will use, resolved up front so a missing setup is reported before a run is created. */
    record Plan(TrackerBinding binding, ConnectionView connection) {}

    Plan plan(UUID workspaceId, UUID userId) {
        TrackerBinding binding = workspaces.tracker(workspaceId).orElseThrow(() -> new TrackerException(Kind.NOT_CONFIGURED, "This project has no issue tracker set; ask an admin, or paste the ticket text"));
        if (!trackers.supports(binding.providerType())) throw new TrackerException(Kind.NOT_CONFIGURED, binding.providerType() + " is not available yet; paste the ticket text");
        ConnectionView conn = connections.findUsable(userId, ConnectionKind.TRACKER, binding.providerType())
            .orElseThrow(() -> new TrackerException(Kind.NOT_CONFIGURED, "Add and verify your " + binding.providerType() + " connection under My connections, or paste the ticket text"));
        return new Plan(binding, conn);
    }

    Ticket fetch(Plan plan, UUID ownerUserId, String key) { return fetch(plan.binding().providerType(), plan.connection().id(), ownerUserId, plan.binding().siteHint(), key); }

    /** Fetch for an existing run: the project's current site setting, the run owner's connection recorded on the run. */
    Ticket fetchForRun(UUID workspaceId, String providerType, UUID connectionId, UUID ownerUserId, String key) {
        String site = workspaces.tracker(workspaceId).map(TrackerBinding::siteHint).orElse(null);
        return fetch(providerType, connectionId, ownerUserId, site, key);
    }

    private Ticket fetch(String providerType, UUID connectionId, UUID ownerUserId, @org.jspecify.annotations.Nullable String site, String key) {
        return trackers.fetch(providerType, new TrackerCredential(connectionId, ownerUserId, () -> connections.reveal(connectionId, ownerUserId), s -> connections.replaceSecret(connectionId, ownerUserId, s)), site, key);
    }

    /** The ticket as text for the normaliser and the investigator: routine status chatter is left out; people's emails never reach here. */
    static String render(Ticket t) {
        StringBuilder sb = new StringBuilder();
        sb.append("Title: ").append(t.title()).append('\n');
        if (t.type() != null) sb.append("Type: ").append(t.type()).append('\n');
        if (t.status() != null) sb.append("Status: ").append(t.status()).append('\n');
        if (t.priority() != null) sb.append("Priority: ").append(t.priority()).append('\n');
        if (!t.components().isEmpty()) sb.append("Components: ").append(String.join(", ", t.components())).append('\n');
        if (!t.labels().isEmpty()) sb.append("Labels: ").append(String.join(", ", t.labels())).append('\n');
        if (t.description() != null && !t.description().isBlank()) sb.append('\n').append(t.description().strip()).append('\n');
        var useful = t.comments().stream().filter(c -> !c.routine()).toList();
        if (!useful.isEmpty()) {
            sb.append("\nComments:\n");
            for (TicketComment c : useful) sb.append("- ").append(c.author()).append(": ").append(c.body().strip()).append('\n');
        }
        return sb.toString().strip();
    }
}
