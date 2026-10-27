package ai.devforge.workspace;

import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * How a project's changes are published.
 *
 * @param targetBranch pull requests target this branch (for example develop); null = the repository's default branch on the host
 * @param branchPrefix the branch created for a ticket is named {@code <prefix><ticket>-<summary>}
 * @param draft        open pull requests as drafts
 */
public record PublishSettings(@Nullable String targetBranch, String branchPrefix, boolean draft) {
    public static final String DEFAULT_PREFIX = "devforge/";
    private static final Pattern BRANCH = Pattern.compile("[A-Za-z0-9._/-]{1,100}");
    private static final Pattern PREFIX = Pattern.compile("[A-Za-z0-9._/-]{0,30}");

    /** @return an error message if {@code branch} (null = not set) cannot be a target branch name, else null */
    public static @Nullable String branchProblem(@Nullable String branch) {
        if (branch != null && (!BRANCH.matcher(branch).matches() || branch.contains("..") || branch.startsWith("-") || branch.startsWith("/") || branch.endsWith("/") || branch.endsWith(".lock"))) {
            return "The target branch is not a valid branch name";
        }
        return null;
    }

    public static PublishSettings defaults() { return new PublishSettings(null, DEFAULT_PREFIX, true); }

    /** @return an error message, or null if the settings are acceptable */
    public @Nullable String problem() {
        String branchProblem = branchProblem(targetBranch);
        if (branchProblem != null) return branchProblem;
        if (!PREFIX.matcher(branchPrefix).matches() || branchPrefix.contains("..") || branchPrefix.startsWith("-") || branchPrefix.startsWith("/")) return "The branch prefix may only use letters, digits and . _ / - and must not start with - or /";
        return null;
    }
}
