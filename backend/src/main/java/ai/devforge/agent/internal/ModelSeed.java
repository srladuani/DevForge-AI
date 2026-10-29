package ai.devforge.agent.internal;

import ai.devforge.agent.ModelInfo;
import ai.devforge.agent.ModelTier;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Built-in starting catalog. Only models whose ids are known are listed; OpenAI and Gemini have none until their adapters can list
 * models live. Context windows and prices are deliberately null (unknown) rather than guessed.
 */
@Component
class ModelSeed {
    private final Map<String, List<ModelInfo>> byProvider = Map.of(
        "ANTHROPIC", List.of(
            new ModelInfo("claude-fable-5-1", "Claude Fable 5.1", ModelTier.STRONG, null, true, true, true, null),
            new ModelInfo("claude-opus-5-5", "Claude Opus 5.5", ModelTier.STRONG, null, true, true, true, null),
            new ModelInfo("claude-sonnet-5-5", "Claude Sonnet 5.5", ModelTier.BALANCED, null, true, true, true, null),
            new ModelInfo("claude-haiku-5-5", "Claude Haiku 5.5", ModelTier.FAST, null, true, true, true, null)));

    List<ModelInfo> forProvider(String adapterType) { return byProvider.getOrDefault(adapterType, List.of()); }
}
