package ai.devforge.execution;

import org.jspecify.annotations.Nullable;

/** @param tool gradle | maven | npm | yarn | pnpm (from the repository profile); @param root sub-directory holding the build, "" for the repository root */
public record ValidationRequest(String tool, String root, @Nullable String testCommand) {}
