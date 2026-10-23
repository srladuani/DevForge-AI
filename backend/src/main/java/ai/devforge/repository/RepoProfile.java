package ai.devforge.repository;

import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * What the platform learned about a repository without any LLM: stack, build tools, how to test it. Derived data, cached per head SHA.
 * Instruction files are listed by name only; their content is untrusted and may only tighten policy.
 */
public record RepoProfile(String headSha, int fileCount, Map<String, Integer> languages, List<BuildTool> buildTools,
                          List<String> instructionFiles, List<String> notes) {

    /** @param tool gradle | maven | npm | yarn | pnpm ... @param toolchain e.g. "java 21", "node >=20" @param frameworks e.g. spring-boot, react, webpack */
    public record BuildTool(String tool, String root, String toolchain, List<String> frameworks, @Nullable String testCommand, @Nullable String buildCommand) {}
}
