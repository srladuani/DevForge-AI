package ai.devforge.workflow.internal;

import ai.devforge.identity.CurrentUsers;
import ai.devforge.workflow.RunViews.PublicationView;
import ai.devforge.workflow.RunViews.PublishRequested;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
class PublishController {
    private final PublishService service;
    private final CurrentUsers users;

    PublishController(PublishService service, CurrentUsers users) {
        this.service = service;
        this.users = users;
    }

    /** Asks for approval to push a branch and open a draft pull request; nothing leaves the platform until someone approves exactly this plan. */
    @PostMapping("/runs/{id}/publish")
    @ResponseStatus(HttpStatus.ACCEPTED)
    PublishRequested publish(@PathVariable UUID id) { return service.request(id, users.require()); }

    @PostMapping("/runs/{id}/publish/retry")
    List<PublicationView> retry(@PathVariable UUID id) { return service.retry(id, users.require()); }

    @GetMapping("/runs/{id}/publications")
    List<PublicationView> publications(@PathVariable UUID id) { return service.list(id, users.require()); }
}
