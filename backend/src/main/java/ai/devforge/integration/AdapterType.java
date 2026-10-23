package ai.devforge.integration;

import ai.devforge.identity.AuthMethod;
import ai.devforge.identity.ConnectionKind;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** What the code can talk to. Adding a vendor = adding an adapter and one entry here; configuring one = a ProviderInstance row. */
public record AdapterType(ConnectionKind kind, String type, String displayName, AdapterAvailability availability, List<AuthMethod> authMethods, @Nullable String defaultBaseUrl) {}
