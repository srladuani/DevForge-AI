package ai.devforge.testsupport;


import com.jayway.jsonpath.JsonPath;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/** Tiny helper so tests read as a conversation between named dev users and the API. */
public final class Api {
    private final MockMvc mvc;

    public Api(MockMvc mvc) { this.mvc = mvc; }

    public ResultActions get(String user, String url) throws Exception { return send(MockMvcRequestBuilders.get(url), user, null); }
    public ResultActions post(String user, String url, String body) throws Exception { return send(MockMvcRequestBuilders.post(url), user, body); }
    public ResultActions put(String user, String url, String body) throws Exception { return send(MockMvcRequestBuilders.put(url), user, body); }
    public ResultActions patch(String user, String url, String body) throws Exception { return send(MockMvcRequestBuilders.patch(url), user, body); }
    public ResultActions delete(String user, String url) throws Exception { return send(MockMvcRequestBuilders.delete(url), user, null); }

    private ResultActions send(MockHttpServletRequestBuilder b, String user, String body) throws Exception {
        b.header("X-Devforge-User", user);
        if (body != null) b.contentType(MediaType.APPLICATION_JSON).content(body);
        return mvc.perform(b);
    }

    public static String json(ResultActions r, String path) throws Exception {
        return String.valueOf((Object) JsonPath.read(r.andReturn().getResponse().getContentAsString(), path));
    }
}
