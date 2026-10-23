package ai.devforge.agent;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.jspecify.annotations.Nullable;

public record AgentSelectionRequest(@NotBlank @Size(max = 40) String provider, @NotBlank @Size(max = 120) String model, @Nullable @Size(max = 120) String fastModel) {}
