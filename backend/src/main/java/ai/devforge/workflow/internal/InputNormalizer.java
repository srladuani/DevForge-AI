package ai.devforge.workflow.internal;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Turns ticket / prompt text into a tracker-neutral summary: identifiers (future search anchors), keywords, gaps, and injection flags.
 * The text itself stays untrusted data; flags only warn, nothing here ever acts on instructions found in it.
 */
@Component
class InputNormalizer {
    private static final Set<String> STOP = Set.of("this", "that", "with", "from", "when", "then", "have", "does", "should", "would", "could", "there", "their", "about",
        "which", "while", "into", "than", "also", "only", "each", "same", "user", "users", "page", "screen", "button", "click", "expected", "actual", "result", "steps", "issue",
        "ticket", "behavior", "behaviour", "application", "shows", "showed", "seen", "found", "after", "before", "being", "other", "some", "more", "most", "very", "just", "case");
    private static final Pattern TICKET_KEY = Pattern.compile("\\b[A-Z][A-Z0-9]{1,9}-\\d+\\b");
    private static final Pattern API_PATH = Pattern.compile("(?<![A-Za-z0-9])/[A-Za-z0-9_\\-]+(?:/[A-Za-z0-9_\\-{}.]+)+");
    private static final Pattern CAMEL = Pattern.compile("\\b[A-Z][a-z0-9]+(?:[A-Z][a-z0-9]*)+\\b");
    private static final Pattern QUOTED = Pattern.compile("[\"'`]([^\"'`\\n]{4,80})[\"'`]");
    private static final Pattern WORD = Pattern.compile("[A-Za-z][A-Za-z0-9_]{3,40}");
    private static final List<Pattern> INJECTION = List.of(
        Pattern.compile("(?i)ignore (all |any )?(previous|prior|above) (instructions|rules|prompts?)"),
        Pattern.compile("(?i)\\b(you are now|act as|pretend to be)\\b.{0,40}\\b(assistant|ai|model|agent)\\b"),
        Pattern.compile("(?i)(reveal|print|show|send|exfiltrate|share).{0,40}(api[ _-]?key|secret|token|password|credential|\\.env)"),
        Pattern.compile("(?i)\\b(run|execute)\\b.{0,30}\\b(curl|wget|bash|sh|powershell|rm -rf|chmod|sudo)\\b"),
        Pattern.compile("(?i)(disable|bypass|skip|turn off).{0,30}(approval|review|safety|policy|guardrail|test)s?"),
        Pattern.compile("(?i)\\b(push|force[- ]push|merge|deploy)\\b.{0,30}\\b(to )?(main|master|prod|production)\\b"),
        Pattern.compile("(?i)system prompt"));

    Map<String, Object> normalize(String kind, String sourceRef, String text, boolean hasClarification, boolean simNeedsInfo) {
        String t = text == null ? "" : text;
        Map<String, Object> out = new LinkedHashMap<>();
        String firstLine = t.strip().lines().findFirst().orElse("");
        out.put("title", sourceRef != null && !sourceRef.isBlank() ? sourceRef + (firstLine.isBlank() ? "" : " " + clip(firstLine, 100)) : clip(firstLine, 120));
        out.put("source", Map.of("kind", kind, "ref", sourceRef == null ? "" : sourceRef, "fetchedFromTracker", false));
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        collect(TICKET_KEY, t, ids, 0);
        collect(API_PATH, t, ids, 0);
        collect(CAMEL, t, ids, 0);
        Matcher q = QUOTED.matcher(t);
        while (q.find() && ids.size() < 25) ids.add(q.group(1).strip());
        out.put("identifiers", new ArrayList<>(ids));
        out.put("keywords", keywords(t, ids));
        List<Map<String, Object>> gaps = new ArrayList<>();
        if (t.strip().length() < 20) gaps.add(Map.of("text", "The description is too short to investigate. What happens, and what did you expect?", "blocking", true));
        if (simNeedsInfo && !hasClarification) gaps.add(Map.of("text", "[SIMULATED] Which user role and environment is this about?", "blocking", true));
        if (!t.toLowerCase().contains("expected") && !t.toLowerCase().contains("should")) gaps.add(Map.of("text", "No expected behaviour was stated.", "blocking", false));
        out.put("gaps", gaps);
        List<String> flags = new ArrayList<>();
        for (Pattern p : INJECTION) {
            Matcher m = p.matcher(t);
            if (m.find()) flags.add("Text addresses an assistant or asks for a risky action: \"" + clip(m.group(), 80) + "\" (treated as data, not followed)");
        }
        out.put("injectionFlags", flags);
        return out;
    }

    @SuppressWarnings("unchecked")
    static boolean hasBlockingGap(Map<String, Object> normalized) {
        return ((List<Map<String, Object>>) normalized.get("gaps")).stream().anyMatch(g -> Boolean.TRUE.equals(g.get("blocking")));
    }

    private static void collect(Pattern p, String text, Set<String> into, int group) {
        Matcher m = p.matcher(text);
        while (m.find() && into.size() < 25) into.add(m.group(group));
    }

    private static List<String> keywords(String text, Set<String> identifiers) {
        LinkedHashSet<String> kws = new LinkedHashSet<>();
        for (String id : identifiers) {
            for (String part : id.split("[/{}.\\-]")) {
                if (part.length() >= 4 && part.matches("[A-Za-z][A-Za-z0-9_]*") && !STOP.contains(part.toLowerCase())) kws.add(part);
            }
        }
        Matcher m = WORD.matcher(text);
        java.util.Map<String, Integer> freq = new java.util.HashMap<>();
        while (m.find()) {
            String w = m.group();
            if (!STOP.contains(w.toLowerCase()) && w.length() >= 4) freq.merge(w.toLowerCase(), 1, Integer::sum);
        }
        freq.entrySet().stream().sorted((a, b) -> b.getValue() != a.getValue().intValue() ? b.getValue() - a.getValue() : b.getKey().length() - a.getKey().length())
            .limit(16).forEach(e -> kws.add(e.getKey()));
        return kws.stream().limit(12).toList();
    }

    private static String clip(String s, int n) { return s.length() <= n ? s : s.substring(0, n) + "…"; }
}
