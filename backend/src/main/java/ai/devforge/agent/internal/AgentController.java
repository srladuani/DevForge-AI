package ai.devforge.agent.internal;

import ai.devforge.agent.ModelCatalog;
import ai.devforge.agent.ProviderOption;
import ai.devforge.identity.CurrentUsers;
import ai.devforge.workspace.WorkspaceAccess;
import ai.devforge.workspace.WorkspaceRole.Permission;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
class AgentController {
    private final ModelCatalog catalog;
    private final CurrentUsers users;
    private final WorkspaceAccess access;

    AgentController(ModelCatalog catalog, CurrentUsers users, WorkspaceAccess access) {
        this.catalog = catalog;
        this.users = users;
        this.access = access;
    }

    /** Providers and models as the caller can use them (their own connections; project policy applied when workspaceId is given). */
    @GetMapping("/agent-providers")
    List<ProviderOption> providers(@RequestParam(required = false) UUID workspaceId) {
        var me = users.require();
        if (workspaceId != null) access.require(workspaceId, me.id(), Permission.VIEW);
        return catalog.optionsFor(me.id(), workspaceId);
    }
}
