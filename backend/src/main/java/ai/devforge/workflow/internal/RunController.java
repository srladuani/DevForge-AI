package ai.devforge.workflow.internal;

import ai.devforge.agent.AgentSelectionRequest;
import ai.devforge.identity.CurrentUsers;
import ai.devforge.platform.DomainException;
import ai.devforge.workflow.RunMode;
import ai.devforge.workflow.RunStatus;
import ai.devforge.workflow.RunViews.ArtifactView;
import ai.devforge.workflow.RunViews.ChangeView;
import ai.devforge.workflow.RunViews.EventView;
import ai.devforge.workflow.RunViews.FindingView;
import ai.devforge.workflow.RunViews.RunView;
import ai.devforge.workflow.RunViews.StepView;
import ai.devforge.workflow.RunViews.ValidationView;
import ai.devforge.workspace.WorkspaceRole.Permission;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.jspecify.annotations.Nullable;

@RestController
@RequestMapping("/api/v1")
class RunController {
    private final RunService service;
    private final RunActions actions;
    private final RunEventStream stream;
    private final CurrentUsers users;

    RunController(RunService service, RunActions actions, RunEventStream stream, CurrentUsers users) {
        this.service = service;
        this.actions = actions;
        this.stream = stream;
        this.users = users;
    }

    record InputBody(@NotBlank @Size(max = 20) String kind, @Nullable @Size(max = 40) String ref, @Nullable @Size(max = 20000) String text) {}

    record CreateRunRequest(@NotNull RunMode mode, @NotNull @Valid InputBody input, @NotEmpty @Size(max = 10) List<UUID> repositoryIds, @NotNull @Valid AgentSelectionRequest agent,
                            @Nullable @Size(max = 2000) String notes) {}

    record ActionBody(@Nullable @Size(max = 20000) String text, @Nullable RunMode mode) {}

    @PostMapping("/workspaces/{workspaceId}/runs")
    ResponseEntity<RunView> create(@PathVariable UUID workspaceId, @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey, @Valid @RequestBody CreateRunRequest r) {
        if (idempotencyKey != null && !idempotencyKey.matches("[A-Za-z0-9_\\-]{8,80}")) throw DomainException.badRequest("INVALID_IDEMPOTENCY_KEY", "Idempotency-Key must be 8-80 letters, digits, - or _");
        var created = service.create(users.require(), workspaceId, new RunService.CreateCommand(r.mode(), new RunService.InputRequest(r.input().kind(), r.input().ref(), r.input().text()),
            r.repositoryIds(), r.agent(), r.notes()), idempotencyKey);
        return created.replay() ? ResponseEntity.ok().header("Idempotent-Replay", "true").body(created.run()) : ResponseEntity.status(HttpStatus.ACCEPTED).body(created.run());
    }

    @GetMapping("/workspaces/{workspaceId}/runs")
    List<RunView> list(@PathVariable UUID workspaceId, @RequestParam(required = false) RunStatus status) { return service.list(workspaceId, users.require(), status); }

    @GetMapping("/runs/{id}")
    RunView get(@PathVariable UUID id) { return service.get(id, users.require()); }

    @GetMapping("/runs/{id}/steps")
    List<StepView> steps(@PathVariable UUID id) { return service.steps(id, users.require()); }

    @GetMapping("/runs/{id}/findings")
    List<FindingView> findings(@PathVariable UUID id) { return service.findings(id, users.require()); }

    @GetMapping("/runs/{id}/artifacts")
    List<ArtifactView> artifacts(@PathVariable UUID id) { return service.artifacts(id, users.require()); }

    @GetMapping("/runs/{id}/changes")
    List<ChangeView> changes(@PathVariable UUID id) { return service.changes(id, users.require()); }

    @GetMapping("/runs/{id}/validations")
    List<ValidationView> validations(@PathVariable UUID id) { return service.validations(id, users.require()); }

    /** Polling variant of the event feed. */
    @GetMapping(value = "/runs/{id}/events", produces = MediaType.APPLICATION_JSON_VALUE)
    List<EventView> events(@PathVariable UUID id, @RequestParam(defaultValue = "0") int after) { return service.events(id, users.require(), after); }

    /** Live variant; resume with the standard Last-Event-ID header (or ?after=). */
    @GetMapping(value = "/runs/{id}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    SseEmitter stream(@PathVariable UUID id, @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId, @RequestParam(defaultValue = "0") int after) {
        service.load(id, users.require(), Permission.VIEW);
        int from = after;
        if (lastEventId != null && lastEventId.matches("\\d{1,9}")) from = Math.max(from, Integer.parseInt(lastEventId));
        return stream.open(id, from);
    }

    @PostMapping("/runs/{id}/actions/{action}")
    RunView act(@PathVariable UUID id, @PathVariable String action, @Valid @RequestBody(required = false) ActionBody body) {
        var me = users.require();
        ActionBody b = body == null ? new ActionBody(null, null) : body;
        return switch (action) {
            case "cancel" -> actions.cancel(id, me);
            case "resume" -> actions.resume(id, me);
            case "answer" -> actions.answer(id, me, b.text());
            case "request-fix" -> actions.requestFix(id, me, b.mode() == null ? RunMode.PROPOSE_FIX : b.mode());
            case "complete" -> actions.complete(id, me);
            case "request-implementation" -> actions.requestImplementation(id, me);
            case "accept" -> actions.accept(id, me);
            case "request-changes" -> actions.requestChanges(id, me, b.text());
            default -> throw DomainException.notFound("UNKNOWN_ACTION", "Unknown action '" + action + "'");
        };
    }
}
