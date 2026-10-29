package ai.devforge.workspace.internal;

import ai.devforge.platform.DomainException;
import ai.devforge.workspace.ProjectTypeDefinition;
import ai.devforge.workspace.ProjectTypes;
import ai.devforge.workspace.WorkspaceKind;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
class ProjectTypeRegistry implements ProjectTypes {
    private final List<ProjectTypeDefinition> types = List.of(
        new ProjectTypeDefinition(WorkspaceKind.SUPPORT, "Support / existing project",
            "Investigate tickets and bugs in existing repositories, propose and validate fixes, and prepare reviewable changes.",
            true, null, List.of("TICKET", "PROMPT"), "support-investigation"),
        new ProjectTypeDefinition(WorkspaceKind.NEW_PROJECT, "New project",
            "Turn requirements, specifications and wireframes into a design, a plan and an implementation.",
            false, "Coming soon", List.of("DOCUMENT", "DESIGN_REF", "PROMPT"), "product-engineering"));

    @Override
    public List<ProjectTypeDefinition> all() { return types; }

    @Override
    public ProjectTypeDefinition get(WorkspaceKind kind) {
        return types.stream().filter(t -> t.kind() == kind).findFirst()
            .orElseThrow(() -> DomainException.badRequest("UNKNOWN_PROJECT_TYPE", "Unknown project type"));
    }
}
