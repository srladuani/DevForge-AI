package ai.devforge.repository.internal;

import ai.devforge.repository.RepoProfile.BuildTool;
import java.util.List;
import java.util.function.Function;

/** One build-system detector. Detectors are a registry: supporting a new stack means adding a bean, not editing existing code. */
interface ProfileDetector {
    /** @param files all paths at the commit; @param read reads a small text file at the commit (null if absent) */
    List<BuildTool> detect(List<String> files, Function<String, String> read);
}
