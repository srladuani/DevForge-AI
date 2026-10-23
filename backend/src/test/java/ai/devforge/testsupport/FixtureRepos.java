package ai.devforge.testsupport;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** Builds small but realistic git repositories in temp directories. Test-only. */
public final class FixtureRepos {
    private FixtureRepos() {}

    public static Path gradleSpring(Path dir) {
        return commit(dir, Map.of(
            "settings.gradle", "rootProject.name = 'acme-service'\n",
            "build.gradle", """
                plugins {
                    id 'java'
                    id 'org.springframework.boot' version '4.1.0'
                }
                java {
                    sourceCompatibility = JavaVersion.VERSION_21
                }
                """,
            "gradlew", "#!/bin/sh\n",
            "gradle/wrapper/gradle-wrapper.properties", "distributionUrl=https\\://services.gradle.org/distributions/gradle-9.5.1-bin.zip\n",
            "CLAUDE.md", "# Rules\nNever push.\n",
            ".env", "DB_PASSWORD=super-secret-password\n",
            "src/main/resources/application-prod.yml", "password: prod-secret\n",
            "src/main/java/com/acme/video/TrainingVideoController.java", """
                package com.acme.video;

                @RequestMapping("/api/v1/training-videos")
                public class TrainingVideoController {
                    private final TrainingVideoBO bo;

                    public TrainingVideoController(TrainingVideoBO bo) { this.bo = bo; }

                    public Object list(String role) {
                        return bo.videosFor(role);
                    }
                }
                """,
            "src/main/java/com/acme/video/TrainingVideoBO.java", """
                package com.acme.video;

                import java.util.List;

                public class TrainingVideoBO {
                    public List<String> videosFor(String role) {
                        // returns every navigation video regardless of role
                        return List.of("how to add user", "audit clearance");
                    }
                }
                """,
            "src/main/java/com/acme/billing/InvoiceService.java", "package com.acme.billing;\npublic class InvoiceService { }\n"));
    }

    public static Path mavenSpring(Path dir) {
        return commit(dir, Map.of(
            "pom.xml", "<project><parent><artifactId>spring-boot-starter-parent</artifactId></parent><properties><java.version>17</java.version></properties></project>\n",
            "mvnw", "#!/bin/sh\n",
            "src/main/java/com/acme/App.java", "package com.acme;\npublic class App { }\n"));
    }

    public static Path reactWebpack(Path dir) {
        return commit(dir, Map.of(
            "package.json", """
                {"name":"admin","scripts":{"start":"webpack serve","build":"webpack","test":"karma start --single-run"},
                 "dependencies":{"react":"^19","antd":"^6"},"devDependencies":{"webpack":"^5","karma":"^6"},"engines":{"node":">=20"}}
                """,
            "yarn.lock", "# yarn\n",
            "src/components/VideoList.jsx", "export default function VideoList({ role }) { return role; }\n",
            "src/api/videos.js", "export const loadVideos = (role) => fetch('/api/v1/training-videos?role=' + role);\n"));
    }

    public static Path reactVite(Path dir) {
        return commit(dir, Map.of(
            "package.json", """
                {"name":"web","scripts":{"dev":"vite","build":"vite build","test":"vitest run"},
                 "dependencies":{"react":"^19"},"devDependencies":{"vite":"^6","vitest":"^3","typescript":"^5"}}
                """,
            "package-lock.json", "{}\n",
            "src/App.tsx", "export default function App() { return null }\n"));
    }

    public static Path commit(Path dir, Map<String, String> files) {
        try {
            Files.createDirectories(dir);
            git(dir, "init", "-q", "-b", "main");
            write(dir, files);
            git(dir, "add", "-A");
            git(dir, "commit", "-q", "-m", "initial");
            return dir.toRealPath();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public static void write(Path dir, Map<String, String> files) throws IOException {
        for (var e : files.entrySet()) {
            Path f = dir.resolve(e.getKey());
            Files.createDirectories(f.getParent());
            Files.writeString(f, e.getValue(), StandardCharsets.UTF_8);
        }
    }

    public static String commitMore(Path dir, Map<String, String> files, String message) {
        try {
            write(dir, files);
            git(dir, "add", "-A");
            git(dir, "commit", "-q", "-m", message);
            return git(dir, "rev-parse", "HEAD").trim();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String git(Path dir, String... args) {
        try {
            var cmd = new java.util.ArrayList<>(List.of("git", "-c", "user.name=Test", "-c", "user.email=test@example.test", "-c", "commit.gpgsign=false", "-C", dir.toString()));
            cmd.addAll(List.of(args));
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (p.waitFor() != 0) throw new IllegalStateException("git " + String.join(" ", args) + " failed: " + out);
            return out;
        } catch (IOException | InterruptedException e) {
            throw new IllegalStateException(e);
        }
    }
}
