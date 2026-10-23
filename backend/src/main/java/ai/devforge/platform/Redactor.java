package ai.devforge.platform;

import java.util.List;
import java.util.regex.Pattern;

/** Best-effort scrubbing of credentials from text that is about to be logged, audited, or shown. */
public final class Redactor {
    private record Rule(Pattern pattern, String replacement) {}

    private static final List<Rule> RULES = List.of(
        new Rule(Pattern.compile("(?i)(bearer\\s+)[A-Za-z0-9._~+/=-]{8,}"), "$1***"),
        new Rule(Pattern.compile("(?i)((?:api[_-]?key|secret|token|password|authorization)\\s*[=:]\\s*)[^\\s\"',;]{4,}"), "$1***"),
        new Rule(Pattern.compile("\\bsk-[A-Za-z0-9_-]{16,}"), "***"),
        new Rule(Pattern.compile("\\bAIza[A-Za-z0-9_-]{20,}"), "***"),
        new Rule(Pattern.compile("\\bgh[pousr]_[A-Za-z0-9]{20,}"), "***"),
        new Rule(Pattern.compile("\\bxox[abprs]-[A-Za-z0-9-]{10,}"), "***"),
        new Rule(Pattern.compile("-----BEGIN [A-Z ]*PRIVATE KEY-----[\\s\\S]*?-----END [A-Z ]*PRIVATE KEY-----"), "-----REDACTED PRIVATE KEY-----"));

    private Redactor() {}

    public static String redact(String text) {
        if (text == null || text.isEmpty()) return text;
        String out = text;
        for (Rule r : RULES) out = r.pattern().matcher(out).replaceAll(r.replacement());
        return out;
    }
}
