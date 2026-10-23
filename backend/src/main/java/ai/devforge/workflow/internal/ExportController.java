package ai.devforge.workflow.internal;

import ai.devforge.identity.CurrentUsers;
import ai.devforge.workflow.RunViews.ExportView;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
class ExportController {
    private final ExportService service;
    private final CurrentUsers users;

    ExportController(ExportService service, CurrentUsers users) {
        this.service = service;
        this.users = users;
    }

    @GetMapping("/runs/{id}/export")
    ExportView export(@PathVariable UUID id) { return service.view(id, users.require()); }

    /** A {@code git am} patch for one repository's change, as a download. */
    @GetMapping(value = "/runs/{id}/changes/{changeId}/export.patch", produces = MediaType.TEXT_PLAIN_VALUE)
    ResponseEntity<byte[]> patch(@PathVariable UUID id, @PathVariable UUID changeId) {
        var user = users.require();
        byte[] body = service.patchFile(id, changeId, user).getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.ok().contentType(new MediaType("text", "plain", StandardCharsets.UTF_8))
            .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(service.fileNameOf(id, changeId, user)).build().toString())
            .header("X-Content-Type-Options", "nosniff").body(body);
    }
}
