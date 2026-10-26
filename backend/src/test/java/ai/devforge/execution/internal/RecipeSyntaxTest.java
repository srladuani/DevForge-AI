package ai.devforge.execution.internal;

import static org.assertj.core.api.Assertions.assertThat;

import ai.devforge.execution.ValidationRequest;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The commands are run by a shell inside the sandbox; a syntax slip would make every validation of that build tool "not run". Parse each one. */
class RecipeSyntaxTest {
    private static boolean parses(String command) throws Exception {
        Process p = new ProcessBuilder("sh", "-n", "-c", command).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes());
        assertThat(p.waitFor()).as("sh -n said: %s for: %s", out, command).isZero();
        return true;
    }

    @Test
    void everyBuildToolRecipeIsValidShell() throws Exception {
        for (String tool : List.of("gradle", "maven", "npm", "yarn", "pnpm")) {
            var r = SandboxValidator.recipe(new ValidationRequest(tool, "", tool.equals("gradle") ? "./gradlew test" : null));
            assertThat(r).as(tool).isNotNull();
            if (r.warm() != null) assertThat(parses(r.warm())).isTrue();
            assertThat(parses(r.test())).isTrue();
        }
        assertThat(parses(SandboxValidator.recipe(new ValidationRequest("script", "", "echo ok && true")).test())).isTrue();
    }
}
