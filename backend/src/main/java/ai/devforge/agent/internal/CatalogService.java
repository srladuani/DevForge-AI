package ai.devforge.agent.internal;

import ai.devforge.agent.AgentSelection;
import ai.devforge.agent.AgentSelectionRequest;
import ai.devforge.agent.ModelCatalog;
import ai.devforge.agent.ModelInfo;
import ai.devforge.agent.ModelTier;
import ai.devforge.agent.ProviderOption;
import ai.devforge.identity.ConnectionKind;
import ai.devforge.identity.Connections;
import ai.devforge.integration.AdapterAvailability;
import ai.devforge.integration.ModelPolicy;
import ai.devforge.integration.ProviderInstanceView;
import ai.devforge.integration.Providers;
import ai.devforge.platform.DomainException;
import ai.devforge.workspace.WorkspacePolicy;
import ai.devforge.workspace.Workspaces;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Builds the provider/model choices a user can make. Model ids come from a built-in seed plus admin-defined custom models and are
 * NOT yet confirmed by live provider listings (that arrives with real adapters). Unknown facts (context window, price) stay null.
 */
@Service
class CatalogService implements ModelCatalog {
    private final Providers providers;
    private final Connections connections;
    private final Workspaces workspaces;
    private final ModelSeed seed;
    private final LiveModels live;

    CatalogService(Providers providers, Connections connections, Workspaces workspaces, ModelSeed seed, LiveModels live) {
        this.providers = providers;
        this.connections = connections;
        this.workspaces = workspaces;
        this.seed = seed;
        this.live = live;
    }

    @Override
    public List<ProviderOption> optionsFor(UUID userId, UUID workspaceId) {
        WorkspacePolicy policy = workspaceId == null ? null : workspaces.policy(workspaceId);
        List<ProviderOption> out = new ArrayList<>();
        for (ProviderInstanceView inst : providers.instances(ConnectionKind.AI_PROVIDER, true)) {
            var usableConnection = connections.findUsable(userId, ConnectionKind.AI_PROVIDER, inst.adapterType());
            boolean usable = usableConnection.isPresent();
            Set<String> liveIds = usableConnection.flatMap(c -> live.forConnection(c.id())).orElse(null);
            boolean allowed = policy == null || policy.allowedProviders().isEmpty() || policy.allowedProviders().contains(inst.adapterType());
            boolean implemented = inst.availability() != AdapterAvailability.PLANNED;
            String reason = !implemented ? "No adapter for this provider exists yet (planned)"
                : !allowed ? "This project does not allow this provider"
                : !usable ? "Add and verify your own " + inst.displayName() + " key under My connections" : null;
            out.add(new ProviderOption(inst.id(), inst.adapterType(), inst.displayName(), inst.availability(), usable, allowed, reason == null, reason, models(inst, liveIds)));
        }
        return out;
    }

    @Override
    public AgentSelection select(UUID userId, UUID workspaceId, AgentSelectionRequest req) {
        ProviderOption option = optionsFor(userId, workspaceId).stream().filter(o -> o.provider().equalsIgnoreCase(req.provider())).findFirst()
            .orElseThrow(() -> DomainException.unprocessable("PROVIDER_NOT_FOUND", "No enabled provider named " + req.provider()));
        if (option.availability() == AdapterAvailability.PLANNED) throw DomainException.unprocessable("PROVIDER_NOT_IMPLEMENTED", option.unavailableReason());
        if (!option.allowedByProject()) throw DomainException.unprocessable("PROVIDER_NOT_ALLOWED", option.unavailableReason());
        if (!option.hasUsableConnection()) throw DomainException.unprocessable("NO_USABLE_CONNECTION", option.unavailableReason());
        ModelInfo model = pick(option, req.model());
        String fast = null;
        if (req.fastModel() != null && !req.fastModel().isBlank()) fast = pick(option, req.fastModel()).id();
        UUID connectionId = connections.findUsable(userId, ConnectionKind.AI_PROVIDER, option.provider()).orElseThrow().id();
        return new AgentSelection(option.provider(), option.instanceId(), model.id(), model.displayName(), fast, connectionId, option.availability() == AdapterAvailability.SIMULATED);
    }

    private static ModelInfo pick(ProviderOption option, String modelId) {
        ModelInfo m = option.models().stream().filter(x -> x.id().equals(modelId)).findFirst()
            .orElseThrow(() -> DomainException.unprocessable("MODEL_NOT_AVAILABLE", "Model " + modelId + " is not offered for " + option.displayName()));
        if (!m.available()) throw DomainException.unprocessable("MODEL_NOT_AVAILABLE", m.unavailableReason());
        return m;
    }

    /**
     * @param liveIds model ids this user's credential could see when last verified; null if unknown. A built-in model is matched by id or by a
     *                dated variant (id-YYYYMMDD); models the key can run that the seed does not know are offered as "discovered".
     */
    private List<ModelInfo> models(ProviderInstanceView inst, Set<String> liveIds) {
        ModelPolicy policy = inst.modelPolicy();
        List<ModelInfo> all = new ArrayList<>();
        for (ModelInfo m : seed.forProvider(inst.adapterType())) {
            boolean seen = liveIds == null || liveIds.stream().anyMatch(id -> id.equals(m.id()) || id.startsWith(m.id() + "-"));
            all.add(seen ? m : new ModelInfo(m.id(), m.displayName(), m.tier(), m.contextWindow(), m.toolCalling(), m.seeded(), false, "Not available to your key"));
        }
        if (liveIds != null) {
            liveIds.stream().sorted().filter(id -> all.stream().noneMatch(m -> id.equals(m.id()) || id.startsWith(m.id() + "-")))
                .forEach(id -> all.add(new ModelInfo(id, id, ModelTier.BALANCED, null, true, false, true, null)));
        }
        policy.customModels().forEach(c -> all.add(new ModelInfo(c.id(), c.displayName() == null ? c.id() : c.displayName(), tier(c.tier()), c.contextWindow(), c.toolCalling(), false, true, null)));
        boolean implemented = inst.availability() != AdapterAvailability.PLANNED;
        return all.stream()
            .filter(m -> policy.allow().isEmpty() || policy.allow().contains(m.id()))
            .filter(m -> !policy.deny().contains(m.id()))
            .map(m -> policy.tierOverrides().containsKey(m.id()) ? new ModelInfo(m.id(), m.displayName(), tier(policy.tierOverrides().get(m.id())), m.contextWindow(), m.toolCalling(), m.seeded(), m.available(), m.unavailableReason()) : m)
            .map(m -> !m.toolCalling() ? unavailable(m, "Model does not support tool calling, which agent runs require") : !implemented ? unavailable(m, "Provider adapter is planned") : m)
            .toList();
    }

    private static ModelInfo unavailable(ModelInfo m, String why) {
        return new ModelInfo(m.id(), m.displayName(), m.tier(), m.contextWindow(), m.toolCalling(), m.seeded(), false, why);
    }

    private static ModelTier tier(String t) {
        try {
            return ModelTier.valueOf(t == null ? "BALANCED" : t.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw DomainException.badRequest("INVALID_TIER", "Tier must be STRONG, BALANCED or FAST");
        }
    }
}
