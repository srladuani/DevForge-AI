package ai.devforge.integration;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class ModelPolicyTest {
    @Test
    void aPolicyStoredAsAnEmptyObjectReadsAsNothingSetNotAsMissingLists() {
        ModelPolicy p = JsonMapper.builder().build().readValue("{}", ModelPolicy.class);
        assertThat(p.allow()).isEmpty();
        assertThat(p.deny()).isEmpty();
        assertThat(p.tierOverrides()).isEmpty();
        assertThat(p.customModels()).isEmpty();
        assertThat(JsonMapper.builder().build().writeValueAsString(p)).contains("\"allow\":[]").contains("\"customModels\":[]");
    }
}
