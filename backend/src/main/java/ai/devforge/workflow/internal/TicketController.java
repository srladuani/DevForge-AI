package ai.devforge.workflow.internal;

import ai.devforge.identity.CurrentUsers;
import ai.devforge.integration.Ticket;
import ai.devforge.integration.TrackerException;
import ai.devforge.platform.DomainException;
import ai.devforge.workspace.WorkspaceAccess;
import ai.devforge.workspace.WorkspaceRole.Permission;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Lets a user see a ticket as the platform will read it, before starting a run. Uses the caller's own tracker connection. */
@RestController
@RequestMapping("/api/v1")
class TicketController {
    private static final Pattern KEY = Pattern.compile("[A-Z][A-Z0-9]{1,9}-\\d{1,9}");
    private final TicketFetcher fetcher;
    private final WorkspaceAccess access;
    private final CurrentUsers users;

    TicketController(TicketFetcher fetcher, WorkspaceAccess access, CurrentUsers users) {
        this.fetcher = fetcher;
        this.access = access;
        this.users = users;
    }

    @GetMapping("/workspaces/{workspaceId}/tickets/{key}")
    Ticket get(@PathVariable UUID workspaceId, @PathVariable String key) {
        var user = users.require();
        access.require(workspaceId, user.id(), Permission.RUN);
        if (!KEY.matcher(key).matches()) throw DomainException.badRequest("INVALID_TICKET_KEY", "Ticket key must look like ABC-123");
        try {
            return fetcher.fetch(fetcher.plan(workspaceId, user.id()), user.id(), key);
        } catch (TrackerException e) {
            throw switch (e.kind()) {
                case NOT_CONFIGURED, AMBIGUOUS_SITE -> DomainException.unprocessable("TRACKER_" + e.kind(), e.getMessage());
                case AUTH_INVALID -> new DomainException("TRACKER_AUTH_INVALID", HttpStatus.UNPROCESSABLE_ENTITY, "The tracker rejected your connection (" + e.getMessage() + "). Update it under My connections.");
                case NOT_FOUND -> DomainException.notFound("TICKET_NOT_FOUND", "The ticket was not found, or your account cannot see it");
                case UNAVAILABLE, BAD_RESPONSE -> new DomainException("TRACKER_UNAVAILABLE", HttpStatus.BAD_GATEWAY, "The tracker could not be read: " + e.getMessage() + ". Try again, or paste the ticket text.");
            };
        }
    }
}
