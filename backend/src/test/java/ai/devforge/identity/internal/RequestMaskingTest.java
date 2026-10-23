package ai.devforge.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;

import ai.devforge.identity.AuthMethod;
import ai.devforge.identity.ConnectionKind;
import org.junit.jupiter.api.Test;

class RequestMaskingTest {
    @Test
    void requestRecordsNeverPrintTheSecret() {
        var create = new ConnectionController.CreateConnectionRequest(ConnectionKind.AI_PROVIDER, "ANTHROPIC", AuthMethod.API_KEY, "k", "sk-very-secret-value");
        var update = new ConnectionController.UpdateConnectionRequest("k", "sk-very-secret-value");
        assertThat(create.toString()).doesNotContain("sk-very-secret-value");
        assertThat(update.toString()).doesNotContain("sk-very-secret-value");
    }
}
