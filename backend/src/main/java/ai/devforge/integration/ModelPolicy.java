package ai.devforge.integration;

import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Admin control over which models of an AI provider instance users may pick.
 *
 * @param allow          if non-empty, only these model ids
 * @param deny           model ids never offered
 * @param tierOverrides  model id to tier label (STRONG|BALANCED|FAST)
 * @param customModels   models the catalog does not know (e.g. behind a proxy)
 */
public record ModelPolicy(List<String> allow, List<String> deny, Map<String, String> tierOverrides, List<CustomModel> customModels) {
    /** A policy stored without some fields (for example an empty {@code {}}) means "nothing set", never a missing list. */
    public ModelPolicy {
        allow = allow == null ? List.of() : List.copyOf(allow);
        deny = deny == null ? List.of() : List.copyOf(deny);
        tierOverrides = tierOverrides == null ? Map.of() : Map.copyOf(tierOverrides);
        customModels = customModels == null ? List.of() : List.copyOf(customModels);
    }

    public static ModelPolicy none() { return new ModelPolicy(List.of(), List.of(), Map.of(), List.of()); }

    public record CustomModel(String id, @Nullable String displayName, String tier, @Nullable Integer contextWindow, boolean toolCalling) {}
}
