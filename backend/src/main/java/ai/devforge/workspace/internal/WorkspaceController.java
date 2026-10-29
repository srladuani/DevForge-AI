package ai.devforge.workspace.internal;

import ai.devforge.identity.CurrentUsers;
import ai.devforge.platform.DomainException;
import ai.devforge.workspace.ProjectTypeDefinition;
import ai.devforge.workspace.ProjectTypes;
import ai.devforge.workspace.PublishSettings;
import ai.devforge.workspace.RepoRole;
import ai.devforge.workspace.TrackerBinding;
import ai.devforge.workspace.WorkspaceKind;
import ai.devforge.workspace.WorkspacePolicy;
import ai.devforge.workspace.WorkspaceRepoView;
import ai.devforge.workspace.WorkspaceRole;
import ai.devforge.workspace.WorkspaceView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.jspecify.annotations.Nullable;

@RestController
@RequestMapping("/api/v1")
class WorkspaceController {
    private final WorkspaceService service;
    private final ProjectTypes projectTypes;
    private final CurrentUsers users;

    WorkspaceController(WorkspaceService service, ProjectTypes projectTypes, CurrentUsers users) {
        this.service = service;
        this.projectTypes = projectTypes;
        this.users = users;
    }

    record CreateWorkspaceRequest(@NotBlank @Size(max = 100) String name, @NotNull WorkspaceKind kind, @Nullable @Valid WorkspacePolicy policy) {}
    record UpdateWorkspaceRequest(@Nullable @Size(min = 1, max = 100) String name, @Nullable WorkspacePolicy policy) {}
    record SetMemberRequest(@NotBlank @Size(max = 320) String email, @NotNull WorkspaceRole role) {}
    record AddRepositoryRequest(@Nullable String path, @Nullable String cloneUrl, @Nullable @Size(max = 100) String name, @NotNull RepoRole role, @Nullable Map<String, Object> hints) {}
    record UpdateRepositoryRequest(@Nullable RepoRole role, @Nullable Map<String, Object> hints, @Nullable @Size(max = 100) String targetBranch) {}

    @GetMapping("/project-types")
    List<ProjectTypeDefinition> projectTypes() { return projectTypes.all(); }

    @GetMapping("/workspaces")
    List<WorkspaceView> list() { return service.listFor(users.require().id()); }

    @PostMapping("/workspaces")
    @ResponseStatus(HttpStatus.CREATED)
    WorkspaceView create(@Valid @RequestBody CreateWorkspaceRequest r) { return service.create(users.require(), r.name(), r.kind(), r.policy()); }

    @GetMapping("/workspaces/{id}")
    WorkspaceView get(@PathVariable UUID id) { return service.get(id, users.require().id()); }

    @PatchMapping("/workspaces/{id}")
    WorkspaceView update(@PathVariable UUID id, @Valid @RequestBody UpdateWorkspaceRequest r) { return service.update(id, users.require().id(), r.name(), r.policy()); }

    record SetTrackerRequest(@NotBlank @jakarta.validation.constraints.Pattern(regexp = "[A-Z][A-Z0-9_]{1,39}") String providerType, @Nullable @Size(max = 200) String siteHint) {}

    /** @param configured false when the project has no tracker (runs then use pasted ticket text) */
    record TrackerView(boolean configured, @Nullable String providerType, @Nullable String siteHint) {
        static TrackerView of(java.util.Optional<TrackerBinding> b) { return new TrackerView(b.isPresent(), b.map(TrackerBinding::providerType).orElse(null), b.map(TrackerBinding::siteHint).orElse(null)); }
    }

    @GetMapping("/workspaces/{id}/tracker")
    TrackerView tracker(@PathVariable UUID id) { return TrackerView.of(service.trackerFor(id, users.require().id())); }

    @PutMapping("/workspaces/{id}/tracker")
    TrackerView setTracker(@PathVariable UUID id, @Valid @RequestBody SetTrackerRequest r) { return TrackerView.of(java.util.Optional.of(service.setTracker(id, users.require().id(), r.providerType(), r.siteHint()))); }

    @DeleteMapping("/workspaces/{id}/tracker")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void clearTracker(@PathVariable UUID id) { service.clearTracker(id, users.require().id()); }

    record PublishSettingsRequest(@Nullable @Size(max = 100) String targetBranch, @NotNull @Size(max = 30) String branchPrefix, boolean draft) {}

    /** How this project's changes are published: pull request target branch, ticket branch prefix, draft or not. */
    @GetMapping("/workspaces/{id}/publishing")
    PublishSettings publishing(@PathVariable UUID id) { return service.publishSettingsFor(id, users.require().id()); }

    @PutMapping("/workspaces/{id}/publishing")
    PublishSettings setPublishing(@PathVariable UUID id, @Valid @RequestBody PublishSettingsRequest r) {
        return service.setPublishSettings(id, users.require().id(), new PublishSettings(r.targetBranch(), r.branchPrefix(), r.draft()));
    }

    @GetMapping("/workspaces/{id}/members")
    List<WorkspaceService.Member> members(@PathVariable UUID id) { return service.members(id, users.require().id()); }

    @PutMapping("/workspaces/{id}/members")
    WorkspaceService.Member setMember(@PathVariable UUID id, @Valid @RequestBody SetMemberRequest r) { return service.setMember(id, users.require().id(), r.email(), r.role()); }

    @DeleteMapping("/workspaces/{id}/members/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void removeMember(@PathVariable UUID id, @PathVariable UUID userId) { service.removeMember(id, users.require().id(), userId); }

    @GetMapping("/workspaces/{id}/repositories")
    List<WorkspaceRepoView> repositories(@PathVariable UUID id) { return service.repositoriesFor(id, users.require().id()); }

    @PostMapping("/workspaces/{id}/repositories")
    @ResponseStatus(HttpStatus.CREATED)
    WorkspaceRepoView addRepository(@PathVariable UUID id, @Valid @RequestBody AddRepositoryRequest r) {
        if (r.cloneUrl() != null && !r.cloneUrl().isBlank()) {
            if (r.path() != null && !r.path().isBlank()) throw DomainException.badRequest("PATH_OR_URL", "Give either a repository URL or a path, not both");
            return service.addRepositoryByUrl(id, users.require().id(), r.cloneUrl(), r.name(), r.role(), r.hints());
        }
        if (r.path() == null || r.path().isBlank()) throw DomainException.badRequest("PATH_REQUIRED", "A local repository path is required");
        return service.addRepository(id, users.require().id(), r.path(), r.name(), r.role(), r.hints());
    }

    @PatchMapping("/workspaces/{id}/repositories/{repoId}")
    WorkspaceRepoView updateRepository(@PathVariable UUID id, @PathVariable UUID repoId, @Valid @RequestBody UpdateRepositoryRequest r) {
        return service.updateRepository(id, users.require().id(), repoId, r.role(), r.hints(), r.targetBranch());
    }

    @DeleteMapping("/workspaces/{id}/repositories/{repoId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void removeRepository(@PathVariable UUID id, @PathVariable UUID repoId) { service.removeRepository(id, users.require().id(), repoId); }

    @PostMapping("/workspaces/{id}/repositories/{repoId}/refresh")
    WorkspaceRepoView refresh(@PathVariable UUID id, @PathVariable UUID repoId) { return service.refreshRepository(id, users.require().id(), repoId); }
}
