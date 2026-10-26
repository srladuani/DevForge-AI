package ai.devforge.integration.internal;

import ai.devforge.identity.CurrentUsers;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
class OAuthController {
    record StartRequest(@Nullable @Size(max = 80) String label) {}

    record Started(String authorizationUrl) {}

    private final OAuthFlows flows;
    private final CurrentUsers users;

    OAuthController(OAuthFlows flows, CurrentUsers users) {
        this.flows = flows;
        this.users = users;
    }

    /** Begins signing in to a tracker with single sign-on. The browser is then sent to the returned address. */
    @PostMapping("/me/oauth/{providerType}/start")
    Started start(@PathVariable String providerType, @Valid @RequestBody(required = false) StartRequest r) {
        String label = r == null || r.label() == null || r.label().isBlank() ? "Single sign-on" : r.label().strip();
        return new Started(flows.start(users.require().id(), providerType, label));
    }

    /** Where the provider sends the browser back. Public by necessity (a browser redirect carries no login); it trusts only the single-use state. */
    @GetMapping("/oauth/callback")
    ResponseEntity<Void> callback(@RequestParam(required = false) @Nullable String code, @RequestParam(required = false) @Nullable String state, @RequestParam(required = false) @Nullable String error) {
        return ResponseEntity.status(HttpStatus.FOUND).header(HttpHeaders.LOCATION, flows.complete(state, code, error)).header("Cache-Control", "no-store").build();
    }
}
