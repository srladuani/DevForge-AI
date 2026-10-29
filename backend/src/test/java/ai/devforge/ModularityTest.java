package ai.devforge;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

class ModularityTest {
    @Test
    void moduleBoundariesAreRespected() {
        ApplicationModules.of(DevForgeBackendApplication.class).verify();
    }
}
