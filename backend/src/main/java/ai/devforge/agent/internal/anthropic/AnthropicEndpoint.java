package ai.devforge.agent.internal.anthropic;

import ai.devforge.identity.ConnectionKind;
import ai.devforge.integration.ProviderInstanceView;
import ai.devforge.integration.Providers;
import org.springframework.stereotype.Component;

/** Where to send Anthropic requests: the admin-configured base URL of the enabled instance (never anything a model supplies), else the public API. */
@Component
class AnthropicEndpoint {
    static final String DEFAULT = "https://api.anthropic.com";
    private final Providers providers;

    AnthropicEndpoint(Providers providers) { this.providers = providers; }

    String baseUrl() {
        String url = providers.findEnabled(ConnectionKind.AI_PROVIDER, "ANTHROPIC").map(ProviderInstanceView::baseUrl).orElse(null);
        String base = url == null || url.isBlank() ? DEFAULT : url.trim();
        return base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
    }
}
