package ai.devforge.identity.internal;

import ai.devforge.audit.AuditEventView;
import ai.devforge.audit.AuditQuery;
import ai.devforge.identity.CurrentUser;
import ai.devforge.identity.CurrentUsers;
import ai.devforge.platform.DomainException;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Platform-admin endpoints (audit trail, vault key rotation). */
@RestController
@RequestMapping("/api/v1/admin")
class AdminController {
    private final CurrentUsers users;
    private final AuditQuery audit;
    private final ConnectionService connections;

    AdminController(CurrentUsers users, AuditQuery audit, ConnectionService connections) {
        this.users = users;
        this.audit = audit;
        this.connections = connections;
    }

    @GetMapping("/audit")
    List<AuditEventView> audit(@RequestParam(defaultValue = "100") int limit, @RequestParam(required = false) String action) {
        requireAdmin();
        return audit.recent(limit, action);
    }

    @PostMapping("/vault/rotate")
    Map<String, Integer> rotate() {
        return Map.of("reEncrypted", connections.rotateAll(requireAdmin().id().toString()));
    }

    private CurrentUser requireAdmin() {
        CurrentUser me = users.require();
        if (!me.admin()) throw DomainException.forbidden("ADMIN_REQUIRED", "Admin role required");
        return me;
    }
}
