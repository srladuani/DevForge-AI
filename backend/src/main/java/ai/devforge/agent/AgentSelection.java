package ai.devforge.agent;

import java.util.UUID;

/** The validated provider/model choice recorded on a run. {@code simulated} is true while only the M1 simulation backs the provider. */
public record AgentSelection(String provider, UUID providerInstanceId, String model, String modelDisplayName, String fastModel, UUID connectionId, boolean simulated) {}
