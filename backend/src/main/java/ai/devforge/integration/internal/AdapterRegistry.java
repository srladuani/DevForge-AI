package ai.devforge.integration.internal;

import ai.devforge.identity.AuthMethod;
import ai.devforge.identity.ConnectionKind;
import ai.devforge.integration.AdapterAvailability;
import ai.devforge.integration.AdapterType;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** The adapter types the code knows. Real adapters register here as they are built (M2 onwards). */
@Component
class AdapterRegistry {
    private static final List<AuthMethod> KEY = List.of(AuthMethod.API_KEY);
    private static final List<AuthMethod> TOKEN = List.of(AuthMethod.PAT, AuthMethod.OAUTH);

    private final List<AdapterType> types;

    AdapterRegistry(@Value("${devforge.agent.mode:simulated}") String agentMode) {
        // Claude is REAL only when the platform is configured for real agents; otherwise it is the labelled simulation.
        AdapterAvailability claude = "real".equalsIgnoreCase(agentMode) ? AdapterAvailability.REAL : AdapterAvailability.SIMULATED;
        this.types = List.of(
        new AdapterType(ConnectionKind.AI_PROVIDER, "ANTHROPIC", "Anthropic (Claude)", claude, KEY, "https://api.anthropic.com"),
        new AdapterType(ConnectionKind.AI_PROVIDER, "OPENAI", "OpenAI", AdapterAvailability.PLANNED, KEY, "https://api.openai.com"),
        new AdapterType(ConnectionKind.AI_PROVIDER, "GEMINI", "Google Gemini", AdapterAvailability.PLANNED, KEY, "https://generativelanguage.googleapis.com"),
        new AdapterType(ConnectionKind.AI_PROVIDER, "OPENAI_COMPATIBLE", "OpenAI-compatible endpoint", AdapterAvailability.PLANNED, KEY, null),
        new AdapterType(ConnectionKind.GIT_HOST, "GITHUB", "GitHub", AdapterAvailability.REAL, List.of(AuthMethod.PAT), "https://api.github.com"),
        new AdapterType(ConnectionKind.GIT_HOST, "BITBUCKET_CLOUD", "Bitbucket Cloud", AdapterAvailability.PLANNED, TOKEN, "https://bitbucket.org"),
        new AdapterType(ConnectionKind.GIT_HOST, "GITLAB", "GitLab", AdapterAvailability.PLANNED, TOKEN, "https://gitlab.com"),
        new AdapterType(ConnectionKind.TRACKER, "JIRA_MCP", "Jira (via MCP)", AdapterAvailability.REAL, List.of(AuthMethod.OAUTH, AuthMethod.PAT, AuthMethod.API_KEY), "https://mcp.atlassian.com/v2/mcp?tools=all"));
    }

    List<AdapterType> all() { return types; }

    Optional<AdapterType> find(ConnectionKind kind, String type) {
        return types.stream().filter(t -> t.kind() == kind && t.type().equalsIgnoreCase(type)).findFirst();
    }
}
