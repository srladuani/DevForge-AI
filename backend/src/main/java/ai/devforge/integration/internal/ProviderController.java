package ai.devforge.integration.internal;

import ai.devforge.identity.ConnectionKind;
import ai.devforge.identity.CurrentUser;
import ai.devforge.identity.CurrentUsers;
import ai.devforge.integration.AdapterType;
import ai.devforge.integration.ModelPolicy;
import ai.devforge.integration.ProviderInstanceView;
import ai.devforge.platform.DomainException;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.jspecify.annotations.Nullable;

/** Admin-managed provider instances (add / update / disable / remove without a deploy) and the adapter types the code supports. */
@RestController
@RequestMapping("/api/v1")
class ProviderController {
    private final ProviderService service;
    private final AdapterRegistry registry;
    private final CurrentUsers users;

    ProviderController(ProviderService service, AdapterRegistry registry, CurrentUsers users) {
        this.service = service;
        this.registry = registry;
        this.users = users;
    }

    record CreateProviderRequest(@NotNull ConnectionKind kind, @NotBlank @Size(max = 40) String adapterType, @NotBlank @Size(max = 80) String displayName,
                                 @Nullable @Size(max = 500) String baseUrl, @Nullable Boolean enabled, @Nullable ModelPolicy modelPolicy) {}
    record UpdateProviderRequest(@Nullable @Size(min = 1, max = 80) String displayName, @Nullable @Size(max = 500) String baseUrl, @Nullable Boolean enabled, @Nullable ModelPolicy modelPolicy) {}

    /** Any signed-in user may see which adapter types exist (to build connection forms). */
    @GetMapping("/adapter-types")
    List<AdapterType> adapterTypes() {
        users.require();
        return registry.all();
    }

    @GetMapping("/admin/provider-instances")
    List<ProviderInstanceView> list(@RequestParam(required = false) ConnectionKind kind) {
        admin();
        return service.instances(kind, false);
    }

    @PostMapping("/admin/provider-instances")
    @ResponseStatus(HttpStatus.CREATED)
    ProviderInstanceView create(@Valid @RequestBody CreateProviderRequest r) {
        CurrentUser me = admin();
        return service.create(me.id().toString(), r.kind(), r.adapterType(), r.displayName(), r.baseUrl(), r.enabled() == null || r.enabled(), r.modelPolicy());
    }

    @PatchMapping("/admin/provider-instances/{id}")
    ProviderInstanceView update(@PathVariable UUID id, @Valid @RequestBody UpdateProviderRequest r) {
        return service.update(admin().id().toString(), id, r.displayName(), r.baseUrl(), r.enabled(), r.modelPolicy());
    }

    @DeleteMapping("/admin/provider-instances/{id}")
    Map<String, Object> remove(@PathVariable UUID id) {
        boolean removed = service.remove(admin().id().toString(), id);
        return Map.of("removed", removed, "disabled", !removed);
    }

    private CurrentUser admin() {
        CurrentUser me = users.require();
        if (!me.admin()) throw DomainException.forbidden("ADMIN_REQUIRED", "Admin role required");
        return me;
    }
}
