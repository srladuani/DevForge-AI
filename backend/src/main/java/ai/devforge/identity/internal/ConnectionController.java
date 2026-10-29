package ai.devforge.identity.internal;

import ai.devforge.identity.AuthMethod;
import ai.devforge.identity.ConnectionKind;
import ai.devforge.identity.ConnectionView;
import ai.devforge.identity.CurrentUsers;
import ai.devforge.platform.DomainException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.jspecify.annotations.Nullable;

/** The caller's own connections. Secrets are write-only: no endpoint ever returns one. */
@RestController
@RequestMapping("/api/v1")
class ConnectionController {
    private final ConnectionService service;
    private final CurrentUsers users;

    ConnectionController(ConnectionService service, CurrentUsers users) {
        this.service = service;
        this.users = users;
    }

    record CreateConnectionRequest(@NotNull ConnectionKind kind, @NotBlank @Size(max = 40) String providerType, @NotNull AuthMethod authMethod,
                                   @NotBlank @Size(max = 80) String label, @NotBlank @Size(max = 4096) String secret,
                                   @Nullable @Size(max = 200) String scope) {
        @Override public String toString() { return "CreateConnectionRequest[kind=" + kind + ", providerType=" + providerType + ", secret=***]"; }
    }

    record UpdateConnectionRequest(@Nullable @Size(min = 1, max = 80) String label, @Nullable @Size(min = 1, max = 4096) String secret,
                                   @Nullable @Size(max = 200) String scope) {
        @Override public String toString() { return "UpdateConnectionRequest[label=" + label + ", secret=***]"; }
    }

    @GetMapping("/me/connections")
    List<ConnectionView> list() { return service.listFor(users.require().id()); }

    @PostMapping("/me/connections")
    @ResponseStatus(HttpStatus.CREATED)
    ConnectionView create(@Valid @RequestBody CreateConnectionRequest r) {
        return service.create(users.require().id(), r.kind(), r.providerType().toUpperCase(), r.authMethod(), r.label(), r.secret(), r.scope());
    }

    @PatchMapping("/me/connections/{id}")
    ConnectionView update(@PathVariable UUID id, @Valid @RequestBody UpdateConnectionRequest r) {
        if (r.label() == null && r.secret() == null && r.scope() == null) throw DomainException.badRequest("NOTHING_TO_UPDATE", "Provide label, secret and/or scope");
        return service.update(id, users.require().id(), r.label(), r.secret(), r.scope());
    }

    @DeleteMapping("/me/connections/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@PathVariable UUID id) { service.delete(id, users.require().id()); }

    @PostMapping("/me/connections/{id}/verify")
    ConnectionView verify(@PathVariable UUID id) { return service.verify(id, users.require().id()); }
}
