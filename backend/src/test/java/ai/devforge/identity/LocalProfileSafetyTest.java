package ai.devforge.identity;

import static org.assertj.core.api.Assertions.assertThat;

import ai.devforge.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;
import org.springframework.beans.factory.annotation.Autowired;

/** The "local" profile trusts a header as identity, so it must only ever listen on the loopback interface. */
class LocalProfileSafetyTest extends IntegrationTest {
    @Autowired Environment env;

    @Test
    void devAuthProfileBindsToLoopbackOnly() {
        assertThat(env.getProperty("devforge.security.dev-auth-enabled", Boolean.class)).isTrue();
        assertThat(env.getProperty("server.address")).isEqualTo("127.0.0.1");
    }
}
