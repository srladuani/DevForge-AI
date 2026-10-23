package ai.devforge.repository.internal;

import ai.devforge.repository.RepoProfile.BuildTool;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Component
class NodeDetector implements ProfileDetector {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final List<String> FRAMEWORKS = List.of("react-native", "react", "next", "vue", "@angular/core", "svelte", "express", "vite", "webpack", "react-scripts",
        "antd", "tailwindcss", "typescript");
    private static final List<String> TEST_FRAMEWORKS = List.of("vitest", "jest", "karma", "mocha", "@playwright/test", "cypress");

    @Override
    public List<BuildTool> detect(List<String> files, Function<String, String> read) {
        List<String> pkgs = files.stream().filter(f -> f.equals("package.json") || f.endsWith("/package.json")).filter(f -> !f.contains("node_modules/"))
            .map(GradleDetector::dir).distinct().sorted().toList();
        List<String> roots = new ArrayList<>();
        for (String p : pkgs) if (roots.stream().noneMatch(r -> r.isEmpty() || p.startsWith(r + "/"))) roots.add(p);
        List<BuildTool> out = new ArrayList<>();
        for (String root : roots) {
            String prefix = root.isEmpty() ? "" : root + "/";
            String text = read.apply(prefix + "package.json");
            if (text == null) continue;
            JsonNode pkg;
            try {
                pkg = JSON.readTree(text);
            } catch (RuntimeException e) {
                continue;
            }
            String manager = files.contains(prefix + "pnpm-lock.yaml") ? "pnpm" : files.contains(prefix + "yarn.lock") ? "yarn" : "npm";
            List<String> frameworks = new ArrayList<>();
            List<String> deps = new ArrayList<>();
            for (String section : List.of("dependencies", "devDependencies")) {
                JsonNode n = pkg.path(section);
                if (n.isObject()) n.propertyNames().forEach(deps::add);
            }
            FRAMEWORKS.stream().filter(deps::contains).forEach(frameworks::add);
            TEST_FRAMEWORKS.stream().filter(deps::contains).forEach(f -> frameworks.add("test:" + f));
            String toolchain = "";
            String engines = pkg.path("engines").path("node").asString("");
            if (!engines.isEmpty()) toolchain = "node " + engines;
            else {
                String nvm = read.apply(prefix + ".nvmrc");
                if (nvm != null && !nvm.isBlank()) toolchain = "node " + nvm.trim();
            }
            JsonNode scripts = pkg.path("scripts");
            String run = manager.equals("npm") ? "npm run " : manager + " ";
            String test = scripts.has("test") ? (manager.equals("npm") ? "npm test" : manager + " test") : null;
            String build = scripts.has("build") ? run + "build" : null;
            out.add(new BuildTool(manager, root, toolchain, frameworks, test, build));
        }
        return out;
    }
}
