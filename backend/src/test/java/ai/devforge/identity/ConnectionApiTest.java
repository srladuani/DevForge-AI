package ai.devforge.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.devforge.IntegrationTest;
import com.jayway.jsonpath.JsonPath;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

class ConnectionApiTest extends IntegrationTest {
    private static final String SECRET = "sk-ant-api03-THIS-MUST-NEVER-LEAK-0123456789";

    @Autowired Connections connections;
    @Autowired Users users;

    private String create(String user, String secret) throws Exception {
        MvcResult r = mvc.perform(as(post("/api/v1/me/connections"), user).contentType(MediaType.APPLICATION_JSON)
                .content("{\"kind\":\"AI_PROVIDER\",\"providerType\":\"anthropic\",\"authMethod\":\"API_KEY\",\"label\":\"my key\",\"secret\":\"" + secret + "\"}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.providerType").value("ANTHROPIC"))
            .andExpect(jsonPath("$.status").value("UNVERIFIED"))
            .andReturn();
        return JsonPath.read(r.getResponse().getContentAsString(), "$.id");
    }

    @Test
    void secretIsWriteOnlyAcrossEveryResponse() throws Exception {
        String id = create("writeonly@example.test", SECRET);
        String list = mvc.perform(as(get("/api/v1/me/connections"), "writeonly@example.test")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(list).contains(id).doesNotContain(SECRET).doesNotContain("secret").doesNotContain("encrypted");
        String patched = mvc.perform(as(patch("/api/v1/me/connections/" + id), "writeonly@example.test").contentType(MediaType.APPLICATION_JSON)
            .content("{\"label\":\"renamed\"}")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(patched).doesNotContain(SECRET);
    }

    @Test
    void storedValueIsCiphertextAndAuditNeverContainsTheSecret() throws Exception {
        create("storage@example.test", SECRET);
        byte[] stored = jdbc.sql("select c.encrypted_secret from connection c join app_user u on u.id = c.user_id where u.email = 'storage@example.test'")
            .query(byte[].class).single();
        assertThat(new String(stored)).doesNotContain("THIS-MUST-NEVER-LEAK");
        var audit = jdbc.sql("select coalesce(string_agg(coalesce(details,''), ' '), '') from audit_event").query(String.class).single();
        assertThat(audit).doesNotContain("THIS-MUST-NEVER-LEAK");
        assertThat(jdbc.sql("select count(*) from audit_event where action = 'CONNECTION_CREATED'").query(Integer.class).single()).isPositive();
    }

    @Test
    void usersCannotSeeTouchOrRevealEachOthersConnections() throws Exception {
        String id = create("owner@example.test", SECRET);
        mvc.perform(as(get("/api/v1/me/connections"), "intruder@example.test")).andExpect(jsonPath("$.length()").value(0));
        mvc.perform(as(delete("/api/v1/me/connections/" + id), "intruder@example.test")).andExpect(status().isNotFound());
        mvc.perform(as(post("/api/v1/me/connections/" + id + "/verify"), "intruder@example.test")).andExpect(status().isNotFound());
        UUID intruder = users.findByEmail("intruder@example.test").orElseThrow().id();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> connections.reveal(UUID.fromString(id), intruder)).hasMessageContaining("another user");
        UUID owner = users.findByEmail("owner@example.test").orElseThrow().id();
        assertThat(connections.reveal(UUID.fromString(id), owner)).isEqualTo(SECRET);
    }

    @Test
    void replacingTheSecretResetsVerificationAndDeleteRemovesTheRow() throws Exception {
        String id = create("replace@example.test", SECRET);
        mvc.perform(as(patch("/api/v1/me/connections/" + id), "replace@example.test").contentType(MediaType.APPLICATION_JSON).content("{\"secret\":\"another-secret-value\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UNVERIFIED"));
        UUID owner = users.findByEmail("replace@example.test").orElseThrow().id();
        assertThat(connections.reveal(UUID.fromString(id), owner)).isEqualTo("another-secret-value");
        mvc.perform(as(delete("/api/v1/me/connections/" + id), "replace@example.test")).andExpect(status().isNoContent());
        mvc.perform(as(get("/api/v1/me/connections"), "replace@example.test")).andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void auditLogIsAppendOnly() {
        jdbc.sql("insert into audit_event (actor, action) values ('t','TEST_APPEND')").update();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.sql("update audit_event set action = 'X' where action = 'TEST_APPEND'").update())
            .hasMessageContaining("append-only");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.sql("delete from audit_event where action = 'TEST_APPEND'").update())
            .hasMessageContaining("append-only");
    }

    @Test
    void nonAdminCannotReadAuditOrRotateVault() throws Exception {
        mvc.perform(as(get("/api/v1/admin/audit"), "plain@example.test")).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ADMIN_REQUIRED"));
        mvc.perform(as(post("/api/v1/admin/vault/rotate"), "plain@example.test")).andExpect(status().isForbidden());
        mvc.perform(as(get("/api/v1/admin/audit"), "admin@example.test")).andExpect(status().isOk());
    }
}
