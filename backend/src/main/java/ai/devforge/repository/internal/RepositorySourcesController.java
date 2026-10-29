package ai.devforge.repository.internal;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** How repositories can be added on this server, so the UI only offers what will work. */
@RestController
@RequestMapping("/api/v1")
class RepositorySourcesController {
    private final PathPolicy policy;

    RepositorySourcesController(PathPolicy policy) { this.policy = policy; }

    /** @param localPaths whether a repository may be registered by a path on the server (off on a shared deployment); a URL is always accepted */
    record RepositorySources(boolean localPaths) {}

    @GetMapping("/repository-sources")
    RepositorySources sources() { return new RepositorySources(policy.allowLocalPaths()); }
}
