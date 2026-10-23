package ai.devforge.agent.internal.real;

import ai.devforge.agent.LlmRequest.ToolSpec;
import ai.devforge.agent.RepoTarget;
import ai.devforge.agent.RunContext;
import ai.devforge.agent.ToolCall;
import ai.devforge.agent.ToolTrace;
import ai.devforge.platform.DomainException;
import ai.devforge.repository.LineHit;
import ai.devforge.repository.RepoReader;
import ai.devforge.repository.SearchHit;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The read-only tools an investigation may use, and the policy that governs them. The model only ever *requests* a call; this class decides.
 * Every call is checked against the run's own repositories, arguments are validated and size-limited, secrets and sensitive files are refused
 * by the repository layer, and nothing here can write, execute or reach the network. Errors become messages for the model, never exceptions.
 */
final class RepoToolbox {
    record Result(String text, boolean denied, ToolTrace trace) {}

    static final String SUBMIT = "submit_findings";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final int MAX_LIST = 150;
    private static final int MAX_GREP = 40;
    private static final int MAX_READ_LINES = 200;

    static final List<ToolSpec> READ_TOOLS = List.of(
        new ToolSpec("repo_list_files", "List files in a repository at the pinned commit, optionally under a folder and/or with one extension.",
            "{\"type\":\"object\",\"properties\":{\"repo\":{\"type\":\"string\",\"description\":\"Repository name\"},\"path_prefix\":{\"type\":\"string\",\"description\":\"Folder to list, e.g. src/main/java\"},\"extension\":{\"type\":\"string\",\"description\":\"File extension such as .java\"}},\"required\":[\"repo\"]}"),
        new ToolSpec("repo_search_files", "Rank the files of one repository by how strongly they match the given keywords (identifiers, error text, endpoint names). Start here.",
            "{\"type\":\"object\",\"properties\":{\"repo\":{\"type\":\"string\"},\"keywords\":{\"type\":\"array\",\"items\":{\"type\":\"string\"},\"description\":\"1 to 8 words or identifiers\"}},\"required\":[\"repo\",\"keywords\"]}"),
        new ToolSpec("repo_grep", "Find lines containing a literal term (case-insensitive) in one repository, optionally under a folder.",
            "{\"type\":\"object\",\"properties\":{\"repo\":{\"type\":\"string\"},\"term\":{\"type\":\"string\",\"description\":\"Literal text, letters digits _ . - and spaces only\"},\"path_prefix\":{\"type\":\"string\"}},\"required\":[\"repo\",\"term\"]}"),
        new ToolSpec("repo_read", "Read a line range of one file (at most 200 lines). Cite what you read.",
            "{\"type\":\"object\",\"properties\":{\"repo\":{\"type\":\"string\"},\"path\":{\"type\":\"string\"},\"start_line\":{\"type\":\"integer\"},\"end_line\":{\"type\":\"integer\"}},\"required\":[\"repo\",\"path\",\"start_line\",\"end_line\"]}"));

    static final ToolSpec SUBMIT_SPEC = new ToolSpec(SUBMIT, "Submit your final conclusions. Call this exactly once, when you are done.",
        "{\"type\":\"object\",\"properties\":{"
            + "\"summary\":{\"type\":\"string\",\"description\":\"Two or three sentences: what you found and how confident you are\"},"
            + "\"findings\":{\"type\":\"array\",\"items\":{\"type\":\"object\",\"properties\":{"
            + "\"label\":{\"type\":\"string\",\"enum\":[\"VERIFIED\",\"SUPPORTED\",\"HYPOTHESIS\",\"UNKNOWN\"]},"
            + "\"text\":{\"type\":\"string\"},"
            + "\"confidence\":{\"type\":\"string\",\"enum\":[\"LOW\",\"MEDIUM\",\"HIGH\"]},"
            + "\"evidence\":{\"type\":\"array\",\"items\":{\"type\":\"object\",\"properties\":{\"repo\":{\"type\":\"string\"},\"path\":{\"type\":\"string\"},\"startLine\":{\"type\":\"integer\"},\"endLine\":{\"type\":\"integer\"}},\"required\":[\"repo\",\"path\",\"startLine\",\"endLine\"]}}},"
            + "\"required\":[\"label\",\"text\",\"confidence\",\"evidence\"]}},"
            + "\"hypotheses\":{\"type\":\"array\",\"items\":{\"type\":\"object\",\"properties\":{\"text\":{\"type\":\"string\"},\"confidence\":{\"type\":\"string\",\"enum\":[\"LOW\",\"MEDIUM\",\"HIGH\"]},\"supporting\":{\"type\":\"array\",\"items\":{\"type\":\"string\"}},\"contradicting\":{\"type\":\"array\",\"items\":{\"type\":\"string\"}}},\"required\":[\"text\",\"confidence\"]}},"
            + "\"unknowns\":{\"type\":\"array\",\"items\":{\"type\":\"string\"}}},"
            + "\"required\":[\"summary\",\"findings\",\"hypotheses\",\"unknowns\"]}");

    private final RunContext ctx;
    private final RepoReader reader;
    private final int maxOutputChars;

    RepoToolbox(RunContext ctx, RepoReader reader, int maxOutputChars) {
        this.ctx = ctx;
        this.reader = reader;
        this.maxOutputChars = maxOutputChars;
    }

    Result execute(ToolCall call) {
        String args = call.argumentsJson() == null ? "{}" : call.argumentsJson();
        if (READ_TOOLS.stream().noneMatch(t -> t.name().equals(call.name()))) {
            return denied(call, "Unknown tool '" + clip(call.name(), 40) + "'. Available tools: " + READ_TOOLS.stream().map(ToolSpec::name).collect(Collectors.joining(", ")) + ", " + SUBMIT + ".");
        }
        try {
            JsonNode a = JSON.readTree(args);
            RepoTarget repo = repoOf(a.path("repo").asString(""));
            if (repo == null) {
                return denied(call, "Unknown repository '" + clip(a.path("repo").asString(""), 60) + "'. Repositories in this run: " + ctx.repos().stream().map(RepoTarget::name).collect(Collectors.joining(", ")) + ".");
            }
            String text = switch (call.name()) {
                case "repo_list_files" -> list(repo, a);
                case "repo_search_files" -> search(repo, a);
                case "repo_grep" -> grep(repo, a);
                default -> read(repo, a);
            };
            return new Result(cap(text), false, new ToolTrace(call.name(), summarize(args), firstLine(text)));
        } catch (DomainException e) {
            return new Result("ERROR " + e.code() + ": " + e.getMessage(), true, new ToolTrace(call.name(), summarize(args), "refused: " + e.code()));
        } catch (RuntimeException e) {
            return new Result("ERROR: the arguments could not be understood. Check them against the tool's description.", true, new ToolTrace(call.name(), summarize(args), "bad arguments"));
        }
    }

    private String list(RepoTarget repo, JsonNode a) {
        String prefix = optional(a, "path_prefix");
        String ext = optional(a, "extension");
        List<String> files = reader.listFiles(repo.repositoryId(), repo.sha(), prefix, ext == null ? null : List.of(ext));
        String body = files.stream().limit(MAX_LIST).collect(Collectors.joining("\n"));
        return files.size() + " file(s)" + (files.size() > MAX_LIST ? " (showing first " + MAX_LIST + "; narrow with path_prefix/extension)" : "") + ":\n" + body;
    }

    private String search(RepoTarget repo, JsonNode a) {
        List<String> kws = new ArrayList<>();
        a.path("keywords").forEach(k -> kws.add(k.asString("")));
        if (kws.isEmpty() || kws.size() > 8) throw DomainException.badRequest("INVALID_KEYWORDS", "Provide between 1 and 8 keywords");
        List<SearchHit> hits = reader.rankFiles(repo.repositoryId(), repo.sha(), kws, null, null, 10);
        if (hits.isEmpty()) return "No files matched those keywords. Try different terms (identifiers, endpoint names, UI labels).";
        return hits.stream().map(h -> String.format("%6.1f  %s  (matched %d keyword(s))", h.score(), h.path(), h.matchedTerms())).collect(Collectors.joining("\n"));
    }

    private String grep(RepoTarget repo, JsonNode a) {
        String term = a.path("term").asString("");
        List<LineHit> hits = reader.grepLines(repo.repositoryId(), repo.sha(), term, optional(a, "path_prefix"), MAX_GREP);
        if (hits.isEmpty()) return "No lines contain '" + clip(term, 60) + "'.";
        return hits.size() + " match(es)" + (hits.size() >= MAX_GREP ? " (limit reached; narrow the term or path_prefix)" : "") + ":\n"
            + hits.stream().map(h -> h.path() + ":" + h.line() + ": " + clip(h.text(), 200)).collect(Collectors.joining("\n"));
    }

    private String read(RepoTarget repo, JsonNode a) {
        int start = a.path("start_line").asInt(1);
        int end = a.path("end_line").asInt(start + 79);
        boolean clamped = end - start + 1 > MAX_READ_LINES;
        if (clamped) end = start + MAX_READ_LINES - 1;
        String text = reader.read(repo.repositoryId(), repo.sha(), a.path("path").asString(""), start, end);
        return (clamped ? "(range shortened to " + MAX_READ_LINES + " lines)\n" : "") + text;
    }

    private RepoTarget repoOf(String name) { return ctx.repos().stream().filter(r -> r.name().equals(name)).findFirst().orElse(null); }

    private Result denied(ToolCall call, String message) {
        return new Result("DENIED: " + message, true, new ToolTrace(call.name(), summarize(call.argumentsJson()), "denied"));
    }

    private static String optional(JsonNode a, String field) {
        String v = a.path(field).asString("");
        return v.isBlank() ? null : v;
    }

    private String cap(String s) {
        return s.length() <= maxOutputChars ? s : s.substring(0, maxOutputChars) + "\n… (output truncated at " + maxOutputChars + " characters; narrow your request)";
    }

    private static String firstLine(String s) {
        int nl = s.indexOf('\n');
        return clip(nl < 0 ? s : s.substring(0, nl), 120);
    }

    private static String summarize(String args) { return clip(args == null ? "" : args.replaceAll("\\s+", " "), 160); }

    private static String clip(String s, int n) { return s == null ? "" : s.length() <= n ? s : s.substring(0, n) + "…"; }
}
