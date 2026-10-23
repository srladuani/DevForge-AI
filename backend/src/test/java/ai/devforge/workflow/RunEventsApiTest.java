package ai.devforge.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

class RunEventsApiTest extends WorkflowTestBase {

    @Test
    void eventsHaveGaplessSequenceNumbersAndCanBeFetchedIncrementally() throws Exception {
        String run = createRun("INVESTIGATE_ONLY", TICKET_TEXT);
        awaitStatus(run, "FINDINGS_AVAILABLE");
        String all = api.get(u("viewer"), "/api/v1/runs/" + run + "/events").andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        List<Integer> seqs = JsonPath.read(all, "$[*].seq");
        assertThat(seqs).isNotEmpty().isEqualTo(java.util.stream.IntStream.rangeClosed(1, seqs.size()).boxed().toList());
        assertThat(JsonPath.<String>read(all, "$[0].type")).isEqualTo("RUN_CREATED");

        int cut = seqs.size() / 2;
        String rest = api.get(u("viewer"), "/api/v1/runs/" + run + "/events?after=" + cut).andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<List<Integer>>read(rest, "$[*].seq")).isEqualTo(seqs.subList(cut, seqs.size()));
        api.get(u("viewer"), "/api/v1/runs/" + run + "/events?after=" + seqs.size()).andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void aFinishedRunStreamsItsHistoryAsServerSentEventsAndThenCloses() throws Exception {
        String run = createRun("INVESTIGATE_ONLY", TICKET_TEXT);
        awaitStatus(run, "FINDINGS_AVAILABLE");
        act(u("owner"), run, "complete", null).andExpect(status().isOk());

        String stream = sse(run, null);
        assertThat(stream).contains("event:RUN_CREATED").contains("event:STATE_CHANGED").contains("id:1").contains("\"type\":\"FINDING_ADDED\"");
        assertThat(stream.indexOf("event:RUN_CREATED")).isLessThan(stream.indexOf("event:FINDING_ADDED"));
    }

    @Test
    void reconnectingWithLastEventIdReplaysOnlyWhatWasMissed() throws Exception {
        String run = createRun("INVESTIGATE_ONLY", TICKET_TEXT);
        awaitStatus(run, "FINDINGS_AVAILABLE");
        act(u("owner"), run, "complete", null).andExpect(status().isOk());
        int total = JsonPath.<List<Integer>>read(api.get(u("owner"), "/api/v1/runs/" + run + "/events").andReturn().getResponse().getContentAsString(), "$[*].seq").size();

        String resumed = sse(run, String.valueOf(total - 2));
        assertThat(resumed).contains("id:" + (total - 1)).contains("id:" + total).doesNotContain("id:" + (total - 2) + "\n").doesNotContain("event:RUN_CREATED");
    }

    @Test
    void outsidersCannotOpenTheStream() throws Exception {
        String run = createRun("INVESTIGATE_ONLY", TICKET_TEXT);
        api.get(u("outsider"), "/api/v1/runs/" + run + "/events").andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/runs/" + run + "/events").accept(MediaType.TEXT_EVENT_STREAM).header("X-Devforge-User", u("outsider"))).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/runs/" + run + "/events").accept(MediaType.TEXT_EVENT_STREAM)).andExpect(status().isUnauthorized());
    }

    private String sse(String run, String lastEventId) throws Exception {
        var req = get("/api/v1/runs/" + run + "/events").accept(MediaType.TEXT_EVENT_STREAM).header("X-Devforge-User", u("viewer"));
        if (lastEventId != null) req.header("Last-Event-ID", lastEventId);
        MvcResult started = mvc.perform(req).andExpect(request().asyncStarted()).andReturn();
        started.getAsyncResult(10_000);
        return mvc.perform(asyncDispatch(started)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }
}
