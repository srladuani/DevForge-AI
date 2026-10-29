package ai.devforge.approval.internal;

import ai.devforge.approval.ApprovalStatus;
import ai.devforge.approval.ApprovalView;
import ai.devforge.identity.CurrentUsers;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.jspecify.annotations.Nullable;

@RestController
@RequestMapping("/api/v1")
class ApprovalController {
    private final ApprovalService service;
    private final CurrentUsers users;

    ApprovalController(ApprovalService service, CurrentUsers users) {
        this.service = service;
        this.users = users;
    }

    enum Decision { APPROVE, REJECT }

    /** @param artifactHash the hash of what the approver was shown; the decision is refused if it no longer matches */
    record DecisionRequest(@NotNull Decision decision, @NotBlank String artifactHash, @Nullable @Size(max = 2000) String comment) {}

    @GetMapping("/workspaces/{workspaceId}/approvals")
    List<ApprovalView> list(@PathVariable UUID workspaceId, @RequestParam(required = false) UUID runId, @RequestParam(required = false) ApprovalStatus status) {
        return service.forWorkspace(workspaceId, users.require().id(), runId, status);
    }

    @PostMapping("/approvals/{id}/decision")
    ApprovalView decide(@PathVariable UUID id, @Valid @RequestBody DecisionRequest r) {
        return service.decide(id, users.require(), r.decision() == Decision.APPROVE, r.artifactHash(), r.comment());
    }
}
