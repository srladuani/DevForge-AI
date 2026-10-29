package ai.devforge.repository.internal;

import ai.devforge.repository.RepoProfile.BuildTool;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
class MavenDetector implements ProfileDetector {
    private static final Pattern JAVA = Pattern.compile("<(?:java\\.version|maven\\.compiler\\.release|maven\\.compiler\\.source|release)>\\s*(\\d+(?:\\.\\d+)?)\\s*<");

    @Override
    public List<BuildTool> detect(List<String> files, Function<String, String> read) {
        List<String> poms = files.stream().filter(f -> f.equals("pom.xml") || f.endsWith("/pom.xml")).map(GradleDetector::dir).distinct().sorted().toList();
        List<String> roots = new ArrayList<>();
        for (String p : poms) if (roots.stream().noneMatch(r -> r.isEmpty() || p.startsWith(r + "/"))) roots.add(p);
        List<BuildTool> out = new ArrayList<>();
        for (String root : roots) {
            String prefix = root.isEmpty() ? "" : root + "/";
            String pom = read.apply(prefix + "pom.xml");
            if (pom == null) continue;
            List<String> frameworks = new ArrayList<>();
            if (pom.contains("spring-boot")) frameworks.add("spring-boot");
            String toolchain = "";
            Matcher m = JAVA.matcher(pom);
            if (m.find()) toolchain = "java " + m.group(1);
            String cmd = files.contains(prefix + "mvnw") ? "./mvnw" : "mvn";
            out.add(new BuildTool("maven", root, toolchain, frameworks, cmd + " -B test", cmd + " -B verify -DskipTests"));
        }
        return out;
    }
}
