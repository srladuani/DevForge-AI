package ai.devforge.platform;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RedactorTest {
    @Test
    void redactsCommonCredentialShapes() {
        String text = "Authorization: Bearer abcdef1234567890 token=ghp_abcdefghijklmnopqrstuvwxyz0123 "
            + "key sk-ant-abcdefghijklmnopqrstuv password: hunter2hunter2 AIzaSyA1234567890abcdefghijkl";
        String out = Redactor.redact(text);
        assertThat(out).doesNotContain("abcdef1234567890", "ghp_abcdefghijklmnopqrstuvwxyz0123", "sk-ant-abcdefghijklmnopqrstuv",
            "hunter2hunter2", "AIzaSyA1234567890abcdefghijkl");
    }

    @Test
    void redactsPrivateKeyBlocks() {
        String out = Redactor.redact("x -----BEGIN RSA PRIVATE KEY-----\nMIIabc\n-----END RSA PRIVATE KEY----- y");
        assertThat(out).doesNotContain("MIIabc").contains("REDACTED PRIVATE KEY");
    }

    @Test
    void leavesOrdinaryTextAlone() {
        assertThat(Redactor.redact("Run PBPR-8500 failed in TrainingVideoBOImpl line 42")).isEqualTo("Run PBPR-8500 failed in TrainingVideoBOImpl line 42");
        assertThat(Redactor.redact(null)).isNull();
    }
}
