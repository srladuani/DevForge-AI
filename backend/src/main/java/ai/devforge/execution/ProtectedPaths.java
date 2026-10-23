package ai.devforge.execution;

import java.util.List;
import java.util.regex.Pattern;

/** Which files a change may never touch (secrets) and which only when the approved proposal named them (build and CI configuration). */
public final class ProtectedPaths {
    private static final List<Pattern> NEVER = List.of(
        Pattern.compile("(^|/)\\.env(\\..*)?$"),
        Pattern.compile("(^|/)id_(rsa|dsa|ecdsa|ed25519)(\\.pub)?$"),
        Pattern.compile("\\.(pem|key|p12|pfx|jks|keystore|kdbx|crt)$", Pattern.CASE_INSENSITIVE),
        Pattern.compile("(^|/)application-[^/]*(prod|production)[^/]*\\.(ya?ml|properties)$", Pattern.CASE_INSENSITIVE),
        Pattern.compile("(^|/)(secrets?|credentials?)(/|\\.|$)", Pattern.CASE_INSENSITIVE),
        Pattern.compile("(^|/)\\.(npmrc|pypirc|netrc|git-credentials)$"),
        Pattern.compile("(^|/)\\.git(/|$)"));

    private static final List<Pattern> NEEDS_APPROVAL = List.of(
        Pattern.compile("(^|/)\\.github/"),
        Pattern.compile("(^|/)\\.gitlab-ci\\.ya?ml$"),
        Pattern.compile("(^|/)(Jenkinsfile|bitbucket-pipelines\\.yml|azure-pipelines\\.yml|Dockerfile|docker-compose[^/]*\\.ya?ml)$"),
        Pattern.compile("(^|/)(pom\\.xml|build\\.gradle(\\.kts)?|settings\\.gradle(\\.kts)?|gradle\\.properties|gradlew(\\.bat)?|mvnw(\\.cmd)?|package\\.json)$"),
        Pattern.compile("(^|/)(package-lock\\.json|yarn\\.lock|pnpm-lock\\.yaml|gradle\\.lockfile)$"),
        Pattern.compile("(^|/)\\.mvn/"),
        Pattern.compile("(^|/)gradle/wrapper/"),
        Pattern.compile("(^|/)\\.git[a-z]*$"));

    private ProtectedPaths() {}

    public static boolean never(String rel) { return NEVER.stream().anyMatch(p -> p.matcher(rel).find()); }

    public static boolean needsApproval(String rel) { return NEEDS_APPROVAL.stream().anyMatch(p -> p.matcher(rel).find()); }
}
