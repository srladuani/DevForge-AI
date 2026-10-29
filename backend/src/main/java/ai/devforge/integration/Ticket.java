package ai.devforge.integration;

import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * A ticket in tracker-neutral form. Adapters map their vendor's fields into this and drop what the platform must not carry
 * (people's email addresses, avatars, links to internal APIs). Text is untrusted data.
 *
 * @param tracker     provider type that produced it, e.g. JIRA_MCP
 * @param description markdown, bounded in length
 */
public record Ticket(String tracker, String key, String title, @Nullable String type, @Nullable String status, @Nullable String priority, @Nullable String description,
                     List<String> labels, List<String> components, List<TicketComment> comments, @Nullable Instant updatedAt) {
    /** @param routine heuristically a status update (stand-up notes, deploy notices) rather than information about the problem */
    public record TicketComment(String author, @Nullable Instant created, String body, boolean routine) {}
}
