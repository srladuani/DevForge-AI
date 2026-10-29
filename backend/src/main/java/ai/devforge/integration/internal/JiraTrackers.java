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

    private final OAuthTokens oauthTokens;

    JiraTrackers(Providers providers, OAuthTokens oauthTokens) {
        this.providers = providers;
        this.oauthTokens = oauthTokens;
    }

    @Override
    public boolean supports(String providerType) { return TYPE.equals(providerType); }

    @Override
    public Ticket fetch(String providerType, TrackerCredential credential, @Nullable String siteHint, String key) {
        if (!supports(providerType)) throw new TrackerException(Kind.NOT_CONFIGURED, "Unsupported tracker: " + providerType);
        String secret = credential.secret().get();
        boolean signedIn = ai.devforge.integration.internal.oauth.OAuthSecret.looksLikeOAuth(secret);
        McpClient mcp = new McpClient(endpoint(), signedIn ? oauthTokens.accessToken(credential) : secret, http, Duration.ofSeconds(30), signedIn);
        // servers name their tools' parameters differently across versions, so ask what this one expects instead of assuming
        Map<String, McpClient.Tool> tools = new java.util.HashMap<>();
        mcp.listToolSpecs().forEach(t -> tools.putIfAbsent(t.name(), t));
        McpClient.Tool getIssue = tools.get("getJiraIssue");
        if (getIssue == null) throw new TrackerException(Kind.BAD_RESPONSE, "The tracker does not offer getJiraIssue (" + tools.size() + " tools offered)");
        String cloudId = resolveSite(mcp, siteHint);
        JsonNode issue = mcp.callTool("getJiraIssue", issueArgs(getIssue, cloudId, key, true));
        JsonNode extraComments = null;
        McpClient.Tool listComments = tools.get("listJiraIssueComments");
        if (listComments != null && commentsOf(unwrap(issue)).isEmpty()) {
            try {
                extraComments = mcp.callTool("listJiraIssueComments", issueArgs(listComments, cloudId, key, false));
            } catch (TrackerException e) {
                if (e.kind() == Kind.AUTH_INVALID) throw e; // comments are a bonus: any other failure still lets the ticket itself through
            }
        }
        return map(key, issue, extraComments);
    }

    private static final List<String> KEY_PARAMS = List.of("issueIdOrKey", "issueKey", "issueKeyOrId", "issueIdOrKeys", "key", "issueId", "issue");

    /** Builds the arguments from the parameters the tool declares; an undeclared schema means the older, well-known shape. */
    static Map<String, Object> issueArgs(McpClient.Tool tool, String cloudId, String key, boolean withFields) {
        JsonNode props = tool.inputSchema().path("properties");
        Map<String, Object> args = new java.util.LinkedHashMap<>();
        if (!props.isObject() || props.isEmpty()) {
            args.put("cloudId", cloudId);
            args.put("issueIdOrKey", key);
            if (withFields) {
                args.put("fields", List.of("summary", "issuetype", "status", "priority", "labels", "components", "description", "comment", "updated"));
                args.put("responseContentFormat", "markdown");
            }
            return args;
        }
        if (props.has("cloudId")) args.put("cloudId", cloudId);
        String keyParam = KEY_PARAMS.stream().filter(props::has).findFirst().orElseThrow(() -> new TrackerException(Kind.BAD_RESPONSE,
            tool.name() + " takes parameters DevForge does not recognise: " + String.join(", ", props.propertyNames())));
        args.put(keyParam, key);
        if (withFields && props.has("fields")) {
            List<String> fields = List.of("summary", "issuetype", "status", "priority", "labels", "components", "description", "comment", "updated");
            args.put("fields", "string".equals(props.path("fields").path("type").asString("")) ? String.join(",", fields) : fields);
        }
        if (withFields && props.has("responseContentFormat")) args.put("responseContentFormat", "markdown");
        return args;
    }

    String endpoint() {
        String url = providers.findEnabled(ConnectionKind.TRACKER, TYPE).map(ProviderInstanceView::baseUrl).orElse(null);
        return url == null || url.isBlank() ? DEFAULT_URL : url.trim();
    }

    /**
     * Which Jira site to ask. Browser-style sign-in lists the sites a person can reach; an API token may have no such list. So: use the list when
     * there is one; when the project names the site (a name, an address like acme.atlassian.net, or a cloud id) use that even if the list is empty.
     */
    private static String resolveSite(McpClient mcp, @Nullable String hint) {
        JsonNode reply = mcp.callTool("getAccessibleAtlassianResources", Map.of());
        List<String[]> found = new ArrayList<>();
        siteNodes(reply, 0).forEach(s -> found.add(new String[] {first(s, "id", "cloudId"), first(s, "url", "siteUrl", "baseUrl"), first(s, "name", "displayName")}));
        found.removeIf(s -> s[0].isEmpty());
        String h = hint == null || hint.isBlank() ? null : hint.strip().toLowerCase(Locale.ROOT);
        if (h != null) {
            var match = found.stream().filter(s -> s[0].equalsIgnoreCase(h) || (!s[1].isEmpty() && s[1].toLowerCase(Locale.ROOT).contains(h)) || s[2].equalsIgnoreCase(h)).map(s -> s[0]).findFirst();
            if (match.isPresent()) return match.get();
            // not in the list (or no list): the server also accepts the site's own address in place of a cloud id
            return h.replaceFirst("^https?://", "").replaceAll("/.*$", "");
        }
        if (found.isEmpty()) {
            throw new TrackerException(Kind.AMBIGUOUS_SITE, "The tracker did not list any Jira site for this credential (reply: " + shape(reply, 0)
                + "). Set the Jira site for the project, for example your-company.atlassian.net.");
        }
        if (found.size() > 1) throw new TrackerException(Kind.AMBIGUOUS_SITE, "This credential reaches several Jira sites; set the site for the project");
        return found.get(0)[0];
    }

    /** The site entries wherever the server put them: a list, a list inside an object (at any of a few levels), or a single site object. */
    static List<JsonNode> siteNodes(JsonNode n, int depth) {
        if (n.isArray()) return java.util.stream.StreamSupport.stream(n.spliterator(), false).filter(JsonNode::isObject).toList();
        if (!n.isObject() || depth > 3) return List.of();
        if (!first(n, "id", "cloudId").isEmpty()) return List.of(n);
        for (String known : List.of("resources", "sites", "accessibleResources", "values", "items", "data", "result")) {
            List<JsonNode> r = siteNodes(n.path(known), depth + 1);
            if (!r.isEmpty()) return r;
        }
        for (String name : n.propertyNames()) {
            List<JsonNode> r = siteNodes(n.path(name), depth + 1);
            if (!r.isEmpty()) return r;
        }
        return List.of();
    }

    /** The structure of a reply (field names and sizes, never values), to diagnose a shape that is not understood. */
    static String shape(JsonNode n, int depth) {
        if (n.isArray()) return "list of " + n.size();
        if (!n.isObject()) return n.isString() ? "text" : n.getNodeType().name().toLowerCase(Locale.ROOT);
        if (depth >= 2) return "{…}";
        List<String> parts = new ArrayList<>();
        for (String name : n.propertyNames()) { if (parts.size() >= 12) break; parts.add(name + ": " + shape(n.path(name), depth + 1)); }
        return "{" + String.join(", ", parts) + "}";
    }

    private static String first(JsonNode o, String... names) {
        for (String n : names) { String v = o.path(n).asString(""); if (!v.isBlank()) return v; }
        return "";
    }

    static Ticket map(String key, JsonNode issue) { return map(key, issue, null); }

    /** Some servers wrap the issue ({"issue": {...}}) or return its fields at the top level; accept those shapes too. */
    static JsonNode unwrap(JsonNode issue) {
        JsonNode found = findIssue(issue, 0);
        return found != null ? found : issue;
    }

    /** The object that holds the ticket: it has a "fields" object (the classic shape) or a "summary" itself (flattened). Searched through wrappers such as data, result, issue or content. */
    private static @Nullable JsonNode findIssue(JsonNode n, int depth) {
        if (depth > 4) return null;
        if (n.isString() && n.asString().strip().startsWith("{")) {
            try {
                return findIssue(JSON_TEXT.readTree(n.asString()), depth + 1); // a server may send the JSON as text
            } catch (RuntimeException notJson) {
                return null;
            }
        }
        if (n.isArray()) return n.isEmpty() ? null : findIssue(n.get(0), depth + 1);
        if (!n.isObject()) return null;
        if (n.path("fields").isObject() && !n.path("fields").isEmpty()) return n;
        if (n.path("summary").isString() && !n.path("summary").asString().isBlank()) return n;
        for (String wrapper : List.of("issue", "data", "result", "content", "value")) {
            JsonNode r = findIssue(n.path(wrapper), depth + 1);
            if (r != null) return r;
        }
        return null;
    }

    private static final tools.jackson.databind.json.JsonMapper JSON_TEXT = tools.jackson.databind.json.JsonMapper.builder().build();

    private static JsonNode commentsOf(JsonNode issue) {
        JsonNode f = issue.path("fields").isObject() ? issue.path("fields") : issue;
        return f.path("comment").path("comments").isArray() ? f.path("comment").path("comments") : f.path("comments").isArray() ? f.path("comments") : f.path("comment").isArray() ? f.path("comment") : tools.jackson.databind.node.JsonNodeFactory.instance.arrayNode();
    }

    /** Rich-text bodies (Atlassian Document Format) arrive as a tree of nodes; their text is all that is needed. */
    static String text(JsonNode n) {
        if (n.isString()) return n.asString();
        StringBuilder sb = new StringBuilder();
        collect(n, sb);
        return sb.toString().strip();
    }

    private static void collect(JsonNode n, StringBuilder sb) {
        if (n.isString()) return;
        if (n.path("text").isString()) sb.append(n.path("text").asString());
        if (n.isObject() && n.path("type").asString("").matches("paragraph|heading|listItem|codeBlock|blockquote|tableRow")) { n.path("content").forEach(c -> collect(c, sb)); sb.append('\n'); return; }
        n.path("content").forEach(c -> collect(c, sb));
        if (n.isArray()) n.forEach(c -> collect(c, sb));
    }

    static Ticket map(String key, JsonNode rawIssue, @Nullable JsonNode extraComments) {
        JsonNode issue = unwrap(rawIssue);
        JsonNode f = issue.path("fields").isObject() ? issue.path("fields") : issue;
        if (f.path("summary").asString("").isBlank()) {
            throw new TrackerException(Kind.BAD_RESPONSE, "The ticket data was not in the expected shape (reply: " + shape(rawIssue, 0) + ")");
        }
        List<String> labels = new ArrayList<>();
        f.path("labels").forEach(l -> labels.add(l.asString("")));
        List<String> components = new ArrayList<>();
        f.path("components").forEach(c -> components.add(c.path("name").asString("")));
        List<TicketComment> comments = new ArrayList<>();
        JsonNode commentNodes = commentsOf(issue).isEmpty() && extraComments != null
            ? (extraComments.isArray() ? extraComments : extraComments.path("comments").isArray() ? extraComments.path("comments") : extraComments.path("values").isArray() ? extraComments.path("values") : extraComments.path("items"))
            : commentsOf(issue);
        for (JsonNode c : commentNodes) {
            if (comments.size() >= MAX_COMMENTS) break;
            String body = cap(text(c.path("body")), MAX_COMMENT);
            if (body.isBlank()) continue;
            comments.add(new TicketComment(c.path("author").path("displayName").asString(c.path("author").path("name").asString("Unknown")), time(c.path("created").asString(null)), body, ROUTINE.matcher(body).find()));
        }
        return new Ticket(TYPE, issue.path("key").asString(key), f.path("summary").asString(), name(f, "issuetype"), name(f, "status"), name(f, "priority"),
            f.path("description").isMissingNode() || f.path("description").isNull() ? null : cap(text(f.path("description")), MAX_DESCRIPTION),
            labels.stream().filter(s -> !s.isBlank()).toList(), components.stream().filter(s -> !s.isBlank()).toList(), List.copyOf(comments), time(f.path("updated").asString(null)));
    }

    private static @Nullable String name(JsonNode f, String field) {
        JsonNode v = f.path(field);
        String n = v.isString() ? v.asString("") : v.path("name").asString(v.path("value").asString(""));
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
