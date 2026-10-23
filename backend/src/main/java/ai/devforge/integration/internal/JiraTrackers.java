package ai.devforge.integration.internal;

import ai.devforge.identity.ConnectionKind;
import ai.devforge.integration.ProviderInstanceView;
import ai.devforge.integration.Providers;
import ai.devforge.integration.Ticket;
import ai.devforge.integration.Ticket.TicketComment;
import ai.devforge.integration.TrackerCredential;
import ai.devforge.integration.TrackerException;
import ai.devforge.integration.TrackerException.Kind;
import ai.devforge.integration.Trackers;
import ai.devforge.integration.internal.mcp.McpClient;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/** Reads Jira tickets through an MCP server and maps them to the tracker-neutral {@link Ticket}, dropping people's emails and avatars. */
@Component
class JiraTrackers implements Trackers {
    static final String TYPE = "JIRA_MCP";
    static final String DEFAULT_URL = "https://mcp.atlassian.com/v2/mcp?tools=all";
    private static final Pattern ROUTINE = Pattern.compile("(?i)daily scrum|stand-?up|scrum update|status update|^\\s*(deployed|merged|released)\\b");
    private static final DateTimeFormatter JIRA_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.ROOT);
    private static final int MAX_DESCRIPTION = 20_000;
    private static final int MAX_COMMENTS = 20;
    private static final int MAX_COMMENT = 2_000;

    private final Providers providers;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    JiraTrackers(Providers providers) { this.providers = providers; }

    @Override
    public boolean supports(String providerType) { return TYPE.equals(providerType); }

    @Override
    public Ticket fetch(String providerType, TrackerCredential credential, @Nullable String siteHint, String key) {
        if (!supports(providerType)) throw new TrackerException(Kind.NOT_CONFIGURED, "Unsupported tracker: " + providerType);
        McpClient mcp = new McpClient(endpoint(), credential.secret().get(), http, Duration.ofSeconds(30));
        String cloudId = resolveSite(mcp, siteHint);
        JsonNode issue = mcp.callTool("getJiraIssue", Map.of("cloudId", cloudId, "issueIdOrKey", key,
            "fields", List.of("summary", "issuetype", "status", "priority", "labels", "components", "description", "comment", "updated"), "responseContentFormat", "markdown"));
        return map(key, issue);
    }

    String endpoint() {
        String url = providers.findEnabled(ConnectionKind.TRACKER, TYPE).map(ProviderInstanceView::baseUrl).orElse(null);
        return url == null || url.isBlank() ? DEFAULT_URL : url.trim();
    }

    private static String resolveSite(McpClient mcp, @Nullable String hint) {
        JsonNode sites = mcp.callTool("getAccessibleAtlassianResources", Map.of());
        List<String[]> found = new ArrayList<>();
        sites.forEach(s -> found.add(new String[] {s.path("id").asString(""), s.path("url").asString(""), s.path("name").asString("")}));
        found.removeIf(s -> s[0].isEmpty());
        if (found.isEmpty()) throw new TrackerException(Kind.AUTH_INVALID, "This credential can reach no Jira site");
        if (hint != null && !hint.isBlank()) {
            String h = hint.strip().toLowerCase(Locale.ROOT);
            return found.stream().filter(s -> s[0].equalsIgnoreCase(h) || s[1].toLowerCase(Locale.ROOT).contains(h) || s[2].equalsIgnoreCase(h)).map(s -> s[0]).findFirst()
                .orElseThrow(() -> new TrackerException(Kind.AMBIGUOUS_SITE, "This credential cannot reach the Jira site set for the project"));
        }
        if (found.size() > 1) throw new TrackerException(Kind.AMBIGUOUS_SITE, "This credential reaches several Jira sites; set the site for the project");
        return found.get(0)[0];
    }

    static Ticket map(String key, JsonNode issue) {
        JsonNode f = issue.path("fields");
        if (!f.isObject() || f.path("summary").asString("").isBlank()) throw new TrackerException(Kind.BAD_RESPONSE, "The ticket data was not in the expected shape");
        List<String> labels = new ArrayList<>();
        f.path("labels").forEach(l -> labels.add(l.asString("")));
        List<String> components = new ArrayList<>();
        f.path("components").forEach(c -> components.add(c.path("name").asString("")));
        List<TicketComment> comments = new ArrayList<>();
        for (JsonNode c : f.path("comment").path("comments")) {
            if (comments.size() >= MAX_COMMENTS) break;
            String body = cap(c.path("body").asString(""), MAX_COMMENT);
            if (body.isBlank()) continue;
            comments.add(new TicketComment(c.path("author").path("displayName").asString("Unknown"), time(c.path("created").asString(null)), body, ROUTINE.matcher(body).find()));
        }
        return new Ticket(TYPE, issue.path("key").asString(key), f.path("summary").asString(), name(f, "issuetype"), name(f, "status"), name(f, "priority"),
            f.path("description").isString() ? cap(f.path("description").asString(), MAX_DESCRIPTION) : null,
            labels.stream().filter(s -> !s.isBlank()).toList(), components.stream().filter(s -> !s.isBlank()).toList(), List.copyOf(comments), time(f.path("updated").asString(null)));
    }

    private static @Nullable String name(JsonNode f, String field) {
        String n = f.path(field).path("name").asString("");
        return n.isBlank() ? null : n;
    }

    private static @Nullable Instant time(@Nullable String s) {
        if (s == null) return null;
        try { return OffsetDateTime.parse(s, JIRA_TIME).toInstant(); } catch (RuntimeException e) {
            try { return OffsetDateTime.parse(s).toInstant(); } catch (RuntimeException e2) { return null; }
        }
    }

    private static String cap(String s, int max) { return s.length() <= max ? s : s.substring(0, max) + "\n[truncated]"; }
}
