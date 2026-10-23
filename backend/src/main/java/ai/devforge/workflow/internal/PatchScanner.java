package ai.devforge.workflow.internal;

import ai.devforge.execution.ProtectedPaths;
import ai.devforge.workflow.RunViews.SecretFinding;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** Looks for credentials in the lines a patch adds, before it can leave the platform. Findings name the file and the rule, never the value. */
final class PatchScanner {
    private record Rule(String name, Pattern pattern, String severity) {}

    private static final List<Rule> RULES = List.of(
        new Rule("private key", Pattern.compile("-----BEGIN [A-Z ]*PRIVATE KEY-----"), "BLOCK"),
        new Rule("API key (sk-)", Pattern.compile("\\bsk-[A-Za-z0-9_-]{16,}"), "BLOCK"),
        new Rule("Google API key", Pattern.compile("\\bAIza[A-Za-z0-9_-]{20,}"), "BLOCK"),
        new Rule("GitHub token", Pattern.compile("\\bgh[pousr]_[A-Za-z0-9]{20,}"), "BLOCK"),
        new Rule("Slack token", Pattern.compile("\\bxox[abprs]-[A-Za-z0-9-]{10,}"), "BLOCK"),
        new Rule("AWS access key", Pattern.compile("\\bAKIA[0-9A-Z]{16}\\b"), "BLOCK"),
        new Rule("bearer token", Pattern.compile("(?i)bearer\\s+[A-Za-z0-9._~+/=-]{20,}"), "BLOCK"),
        new Rule("credential assignment", Pattern.compile("(?i)\\b(api[_-]?key|secret|token|password|passwd)\\s*[=:]\\s*[\"']?[^\\s\"',;]{6,}"), "WARN"));

    private PatchScanner() {}

    static List<SecretFinding> scan(String patch) {
        List<SecretFinding> out = new ArrayList<>();
        String file = "(unknown)";
        for (String line : patch.split("\n", -1)) {
            if (line.startsWith("+++ ")) {
                file = line.startsWith("+++ b/") ? line.substring(6) : line.substring(4);
                if (ProtectedPaths.never(file)) add(out, new SecretFinding(file, "restricted file (secrets by name)", "BLOCK"));
                continue;
            }
            if (!line.startsWith("+") || line.startsWith("+++")) continue;
            for (Rule r : RULES) if (r.pattern().matcher(line).find()) add(out, new SecretFinding(file, r.name(), r.severity()));
        }
        return out;
    }

    private static void add(List<SecretFinding> out, SecretFinding f) { if (!out.contains(f)) out.add(f); }
}
