package ai.devforge.execution.internal;

import ai.devforge.execution.Sandbox;
import ai.devforge.execution.SandboxResult;
import ai.devforge.execution.SandboxSpec;
import ai.devforge.execution.SandboxUnavailableException;
import ai.devforge.execution.ValidationReport;
import ai.devforge.execution.ValidationReport.Status;
import ai.devforge.execution.ValidationRequest;
import ai.devforge.execution.Validator;
import ai.devforge.execution.WorkCopy;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import org.springframework.stereotype.Component;

/**
 * Two steps per repository, both inside the sandbox: a dependency step (network on, lockfile-driven, no repo test code) and the test
 * step (network off). Build-tool recipes are data in this class, so a new tool is one more case.
 */
@Component
class SandboxValidator implements Validator {
    private static final Duration WARM_TIMEOUT = Duration.ofMinutes(15);
    private static final Duration TEST_TIMEOUT = Duration.ofMinutes(20);
    /** Gradle only caches what a build resolved, so ask it to resolve every configuration (compile classpaths alone leave test runtime out). */
    private static final String GRADLE_RESOLVE_ALL = "allprojects { tasks.register('devforgeResolveAll') { doLast { configurations.matching { it.canBeResolved }.each { try { it.resolve() } catch (Exception e) { println 'unresolved: ' + it.name } } } } }\n";

    private final Sandbox sandbox;

    SandboxValidator(Sandbox sandbox) { this.sandbox = sandbox; }

    private record Recipe(String warm, String test, Map<String, String> env) {}

    @Override
    public ValidationReport validate(WorkCopy copy, ValidationRequest req, Path cacheDir, BooleanSupplier cancelled) {
        Recipe recipe = recipe(req);
        if (recipe == null) return ValidationReport.notRun("No validation recipe for build tool '" + req.tool() + "'; nothing was run");
        if (!sandbox.available()) return ValidationReport.notRun("No sandbox is available on this server, so no repository code was run");
        try {
            Files.createDirectories(cacheDir);
            if (req.tool().equals("gradle")) Files.writeString(cacheDir.resolve("resolve-all.gradle"), GRADLE_RESOLVE_ALL);
            if (cancelled.getAsBoolean()) return ValidationReport.notRun("Cancelled before the tests ran");
            String cd = req.root() == null || req.root().isBlank() ? "" : "cd '" + req.root().replace("'", "") + "' && ";
            if (recipe.warm() != null) {
                SandboxResult warm = sandbox.run(new SandboxSpec(copy.root(), cd + recipe.warm(), WARM_TIMEOUT, true, recipe.env(), 128 * 1024, cacheDir));
                if (!warm.succeeded()) return new ValidationReport(Status.NOT_RUN, "", -1, 0, 0, 0, List.of(), "Dependencies could not be prepared, so the tests were not run" + (warm.timedOut() ? " (timed out)" : ""), tail(warm.output()));
            }
            if (cancelled.getAsBoolean()) return ValidationReport.notRun("Cancelled before the tests ran");
            SandboxResult test = sandbox.run(new SandboxSpec(copy.root(), cd + recipe.test(), TEST_TIMEOUT, false, recipe.env(), 256 * 1024, cacheDir));
            var totals = JUnitReports.read(copy.root());
            boolean failed = !test.succeeded() || totals.failures() + totals.errors() > 0;
            String note = test.timedOut() ? "The tests did not finish in time" : totals.tests() < 0 ? "No test report was found; the result is the exit code only" : "";
            return new ValidationReport(failed ? Status.FAILED : Status.PASSED, recipe.test(), totals.tests(), totals.failures(), totals.errors(), totals.skipped(), totals.failing(), note, tail(test.output()));
        } catch (SandboxUnavailableException e) {
            return ValidationReport.notRun(e.getMessage());
        } catch (IOException e) {
            return ValidationReport.notRun("Could not prepare the dependency cache");
        }
    }

    private static Recipe recipe(ValidationRequest r) {
        String custom = r.testCommand();
        return switch (r.tool()) {
            case "gradle" -> new Recipe("(./gradlew || gradle) --no-daemon -q --init-script /cache/resolve-all.gradle devforgeResolveAll",
                (custom != null && custom.startsWith("./gradlew") ? custom : "./gradlew test") + " --offline --no-daemon", Map.of("GRADLE_USER_HOME", "/cache/gradle"));
            case "maven" -> new Recipe("./mvnw -B -q dependency:go-offline -Dmaven.repo.local=/cache/m2", "./mvnw -B -o test -Dmaven.repo.local=/cache/m2", Map.of());
            case "npm" -> new Recipe("npm ci --ignore-scripts --no-audit --no-fund", custom != null ? custom : "npm test", Map.of("npm_config_cache", "/cache/npm", "CI", "true"));
            case "yarn" -> new Recipe("yarn install --frozen-lockfile --ignore-scripts", custom != null ? custom : "yarn test", Map.of("YARN_CACHE_FOLDER", "/cache/yarn", "CI", "true"));
            case "pnpm" -> new Recipe("pnpm install --frozen-lockfile --ignore-scripts", custom != null ? custom : "pnpm test", Map.of("npm_config_store_dir", "/cache/pnpm", "CI", "true"));
            case "script" -> custom == null ? null : new Recipe(null, custom, Map.of("CI", "true"));
            default -> null;
        };
    }

    private static String tail(String output) {
        String o = output == null ? "" : output.strip();
        return o.length() <= 4000 ? o : "…" + o.substring(o.length() - 4000);
    }
}
