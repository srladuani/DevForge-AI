package ai.devforge.repository.internal;

import ai.devforge.repository.RepoProfile;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/** Deterministic repo profile: file histogram, build tools, instruction files. No LLM involved. */
@Service
class ProfileService {
    private static final Map<String, String> LANG = Map.ofEntries(
        Map.entry("java", "Java"), Map.entry("kt", "Kotlin"), Map.entry("groovy", "Groovy"), Map.entry("js", "JavaScript"), Map.entry("jsx", "JavaScript"),
        Map.entry("ts", "TypeScript"), Map.entry("tsx", "TypeScript"), Map.entry("py", "Python"), Map.entry("sql", "SQL"), Map.entry("scss", "CSS"),
        Map.entry("css", "CSS"), Map.entry("html", "HTML"), Map.entry("go", "Go"), Map.entry("rb", "Ruby"), Map.entry("cs", "C#"));
    private static final Pattern INSTRUCTIONS = Pattern.compile("(?i)^(claude\\.md|agents\\.md|readme(\\.[a-z]+)?|contributing(\\.[a-z]+)?|\\.cursorrules)$");
    private static final int MAX_FILE_BYTES = 200_000;

    private final GitCli git;
    private final PathPolicy policy;
    private final List<ProfileDetector> detectors;

    ProfileService(GitCli git, PathPolicy policy, List<ProfileDetector> detectors) {
        this.git = git;
        this.policy = policy;
        this.detectors = detectors;
    }

    RepoProfile compute(Path repo, String sha) {
        List<String> files = git.lsTree(repo, sha).stream().filter(f -> !policy.isDenied(f)).toList();
        Function<String, String> read = path -> {
            if (!files.contains(path)) return null;
            String t = git.cat(repo, sha, path);
            return t == null ? null : (t.length() > MAX_FILE_BYTES ? t.substring(0, MAX_FILE_BYTES) : t);
        };
        Map<String, Integer> counts = new LinkedHashMap<>();
        Map<String, Integer> raw = new java.util.HashMap<>();
        for (String f : files) {
            int dot = f.lastIndexOf('.');
            if (dot < 0 || f.indexOf("node_modules/") >= 0) continue;
            String lang = LANG.get(f.substring(dot + 1).toLowerCase());
            if (lang != null) raw.merge(lang, 1, Integer::sum);
        }
        raw.entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue()).limit(6).forEach(e -> counts.put(e.getKey(), e.getValue()));
        var tools = new ArrayList<RepoProfile.BuildTool>();
        List<String> notes = new ArrayList<>();
        for (ProfileDetector d : detectors) {
            try {
                tools.addAll(d.detect(files, read));
            } catch (RuntimeException e) {
                notes.add("A build detector failed: " + e.getClass().getSimpleName());
            }
        }
        if (tools.isEmpty()) notes.add("No known build tool detected; only generic search tools apply and validation is unavailable.");
        List<String> instr = files.stream().filter(f -> !f.contains("/") && INSTRUCTIONS.matcher(f).matches()).toList();
        return new RepoProfile(sha, files.size(), counts, tools, instr, notes);
    }
}
