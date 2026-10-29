package ai.devforge.repository.internal;

import ai.devforge.repository.RepoProfile.BuildTool;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
class GradleDetector implements ProfileDetector {
    private static final Pattern JAVA_VERSION = Pattern.compile(
        "(?:JavaVersion\\.VERSION_(\\d+(?:_\\d+)?)|JavaLanguageVersion\\.of\\(\\s*(\\d+)\\s*\\)|(?:sourceCompatibility|release)\\s*=\\s*['\"]?(\\d+(?:\\.\\d+)?)['\"]?)");
    private static final Pattern WRAPPER = Pattern.compile("gradle-([0-9][0-9A-Za-z.\\-]*)-(?:bin|all)\\.zip");

    @Override
    public List<BuildTool> detect(List<String> files, Function<String, String> read) {
        List<BuildTool> out = new ArrayList<>();
        for (String root : roots(files)) {
            String prefix = root.isEmpty() ? "" : root + "/";
            boolean wrapper = files.contains(prefix + "gradlew");
            String script = firstNonNull(read.apply(prefix + "build.gradle"), read.apply(prefix + "build.gradle.kts"), "");
            List<String> frameworks = new ArrayList<>();
            if (script.contains("org.springframework.boot")) frameworks.add("spring-boot");
            if (script.contains("kotlin")) frameworks.add("kotlin");
            List<String> toolchain = new ArrayList<>();
            Matcher jv = JAVA_VERSION.matcher(script);
            if (jv.find()) toolchain.add("java " + firstNonNull(jv.group(1), jv.group(2), jv.group(3)).replace('_', '.'));
            String props = read.apply(prefix + "gradle/wrapper/gradle-wrapper.properties");
            if (props != null) {
                Matcher w = WRAPPER.matcher(props);
                if (w.find()) toolchain.add("gradle " + w.group(1));
            }
            String cmd = (wrapper ? "./gradlew" : "gradle");
            out.add(new BuildTool("gradle", root, String.join(", ", toolchain), frameworks, cmd + " test", cmd + " build -x test"));
        }
        return out;
    }

    /** Project roots: directories with settings.gradle(.kts), else with build.gradle(.kts) that are not inside another root. */
    private static List<String> roots(List<String> files) {
        List<String> settings = files.stream().filter(f -> f.endsWith("settings.gradle") || f.endsWith("settings.gradle.kts")).map(GradleDetector::dir).distinct().toList();
        if (!settings.isEmpty()) return settings;
        List<String> builds = files.stream().filter(f -> f.endsWith("build.gradle") || f.endsWith("build.gradle.kts")).map(GradleDetector::dir).distinct().sorted().toList();
        List<String> roots = new ArrayList<>();
        for (String b : builds) if (roots.stream().noneMatch(r -> r.isEmpty() || b.startsWith(r + "/"))) roots.add(b);
        return roots;
    }

    static String dir(String path) {
        int i = path.lastIndexOf('/');
        return i < 0 ? "" : path.substring(0, i);
    }

    @SafeVarargs
    static <T> T firstNonNull(T... xs) {
        for (T x : xs) if (x != null) return x;
        return null;
    }
}
