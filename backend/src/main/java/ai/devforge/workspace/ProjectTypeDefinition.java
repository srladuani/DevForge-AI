package ai.devforge.workspace;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * A project type is a plug-in over the shared core: which inputs it accepts and which workflow it runs. Registering a new one
 * (e.g. the future "new project" type) must not require changes to the core modules.
 *
 * @param enabled        selectable today
 * @param disabledReason shown in the UI when not enabled
 * @param workflowId     key of the workflow definition the workflow module runs
 */
public record ProjectTypeDefinition(WorkspaceKind kind, String displayName, String description, boolean enabled, @Nullable String disabledReason,
                                    List<String> inputKinds, String workflowId) {}
