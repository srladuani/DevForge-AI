package ai.devforge.workspace;

import java.util.List;

public interface ProjectTypes {
    List<ProjectTypeDefinition> all();

    ProjectTypeDefinition get(WorkspaceKind kind);
}
