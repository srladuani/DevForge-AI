package ai.devforge.agent;

import org.jspecify.annotations.Nullable;

/**
 * @param contextWindow       null when unknown (never guessed)
 * @param seeded              true when the id comes from the built-in seed and has not been confirmed by a live provider listing
 * @param available           selectable for runs
 * @param unavailableReason   why not, when {@code available} is false
 */
public record ModelInfo(String id, String displayName, ModelTier tier, @Nullable Integer contextWindow, boolean toolCalling, boolean seeded, boolean available,
                        @Nullable String unavailableReason) {}
