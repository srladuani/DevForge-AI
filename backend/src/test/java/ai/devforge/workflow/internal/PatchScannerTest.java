package ai.devforge.workflow.internal;

import static org.assertj.core.api.Assertions.assertThat;

import ai.devforge.workflow.RunViews.SecretFinding;
import java.util.List;
import org.junit.jupiter.api.Test;

class PatchScannerTest {
    private static String patch(String file, String... added) {
        return "diff --git a/" + file + " b/" + file + "\n--- a/" + file + "\n+++ b/" + file + "\n@@ -1 +1,2 @@\n context\n" + String.join("\n", java.util.Arrays.stream(added).map(a -> "+" + a).toList()) + "\n";
    }

    @Test
    void findsCredentialsOnAddedLinesAndNamesTheFileAndRuleButNeverTheValue() {
        List<SecretFinding> f = PatchScanner.scan(patch("src/A.java", "String k = \"sk-ant-api03-abcdefghijklmnopqrstuvwxyz\";", "String t = \"ghp_abcdefghijklmnopqrstuvwxyz0123456789\";"));
        assertThat(f).extracting(SecretFinding::rule).containsExactly("API key (sk-)", "GitHub token");
        assertThat(f).allMatch(s -> s.severity().equals("BLOCK") && s.file().equals("src/A.java"));
        assertThat(f.toString()).doesNotContain("abcdefghijklmnopqrstuvwxyz");
    }

    @Test
    void ignoresRemovedAndContextLinesAndTreatsAssignmentsAsWarnings() {
        String p = "--- a/x\n+++ b/x\n@@ -1,2 +1,2 @@\n-String k = \"AKIAABCDEFGHIJKLMNOP\";\n token = \"AKIAABCDEFGHIJKLMNOP\"\n+int retries = 3;\n+String password = \"changeit-please\";\n";
        List<SecretFinding> f = PatchScanner.scan(p);
        assertThat(f).hasSize(1);
        assertThat(f.getFirst().severity()).isEqualTo("WARN");
    }

    @Test
    void aFileThatIsSecretByNameBlocksWhateverItContains() {
        assertThat(PatchScanner.scan(patch(".env", "A=1"))).extracting(SecretFinding::severity).containsExactly("BLOCK");
        assertThat(PatchScanner.scan(patch("src/B.java", "int x = 1;"))).isEmpty();
    }
}
