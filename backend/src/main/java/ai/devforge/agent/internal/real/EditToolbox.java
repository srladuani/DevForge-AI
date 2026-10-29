package ai.devforge.agent.internal.real;

import ai.devforge.agent.LlmRequest.ToolSpec;
import ai.devforge.agent.RepoTarget;
import ai.devforge.agent.RunContext;
import ai.devforge.agent.ToolCall;
import ai.devforge.agent.ToolTrace;
import ai.devforge.execution.ProtectedPaths;
import ai.devforge.execution.WorkCopies;
import ai.devforge.execution.WorkCopy;
import ai.devforge.execution.WorkCopy.EditRejected;
import ai.devforge.repository.RepoReader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Edit tools over private copies of the run's repositories, bounded by the approved proposal: only the files it lists can be changed, and
 * build or CI files only if it named them. The model requests; this class decides. Reads use the pinned commit, so a file the model has
 * edited is re-read with {@code read_changed}.
 */
final class EditToolbox implements AutoCloseable {
    static final String FINISH = "finish_implementation";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    static final List<ToolSpec> EDIT_TOOLS = List.of(
        new ToolSpec("edit_file", "Replace text in a file listed in the approved proposal. old_text must appear EXACTLY ONCE in the file; include surrounding lines to make it unique.",
            "{\"type\":\"object\",\"properties\":{\"repo\":{\"type\":\"string\"},\"path\":{\"type\":\"string\"},\"old_text\":{\"type\":\"string\"},\"new_text\":{\"type\":\"string\"}},\"required\":[\"repo\",\"path\",\"old_text\",\"new_text\"]}"),
        new ToolSpec("create_file", "Create a new file that the approved proposal lists as new.",
            "{\"type\":\"object\",\"properties\":{\"repo\":{\"type\":\"string\"},\"path\":{\"type\":\"string\"},\"content\":{\"type\":\"string\"}},\"required\":[\"repo\",\"path\",\"content\"]}"),
        new ToolSpec("read_changed", "Read lines of a file from your working copy, including your edits, with line numbers. Pass start_line and end_line to read only what you need (at most 150 lines are shown).",
            "{\"type\":\"object\",\"properties\":{\"repo\":{\"type\":\"string\"},\"path\":{\"type\":\"string\"},\"start_line\":{\"type\":\"integer\"},\"end_line\":{\"type\":\"integer\"}},\"required\":[\"repo\",\"path\"]}"));

    static final ToolSpec FINISH_SPEC = new ToolSpec(FINISH, "Call exactly once when the implementation is complete (or you cannot complete it).",
        "{\"type\":\"object\",\"properties\":{\"summary\":{\"type\":\"string\"},\"notes\":{\"type\":\"array\",\"items\":{\"type\":\"string\"}}},\"required\":[\"summary\"]}");

    record Done(String summary, List<String> notes) {}

    private final RunContext ctx;
    private final WorkCopies copies;
    private final Function<RepoTarget, Path> sourceOf;
    private final RepoToolbox reads;
    private final Map<String, Set<String>> allowed = new HashMap<>();
    private final Map<String, Set<String>> approvedProtected = new HashMap<>();
    private final Map<String, WorkCopy> open = new LinkedHashMap<>();
    private int edits;

    EditToolbox(RunContext ctx, RepoReader reader, WorkCopies copies, Function<RepoTarget, Path> sourceOf, List<String> approvedFiles, int maxOutputChars) {
        this.ctx = ctx;
        this.copies = copies;
        this.sourceOf = sourceOf;
        this.reads = new RepoToolbox(ctx, reader, maxOutputChars);
        for (String entry : approvedFiles) {
            int colon = entry.indexOf(':');
            if (colon < 0) continue;
            String repo = entry.substring(0, colon);
            String path = entry.substring(colon + 1);
            if (path.endsWith(ProposalParser.NEW_SUFFIX)) path = path.substring(0, path.length() - ProposalParser.NEW_SUFFIX.length());
            allowed.computeIfAbsent(repo, k -> new HashSet<>()).add(path);
            if (ProtectedPaths.needsApproval(path)) approvedProtected.computeIfAbsent(repo, k -> new HashSet<>()).add(path);
        }
    }

    int edits() { return edits; }

    ToolLoop.Executed execute(ToolCall call) {
        if (RepoToolbox.READ_TOOLS.stream().anyMatch(t -> t.name().equals(call.name()))) {
            RepoToolbox.Result r = reads.execute(call);
            return new ToolLoop.Executed(r.text(), r.denied(), r.trace(), true);
        }
        if (EDIT_TOOLS.stream().noneMatch(t -> t.name().equals(call.name()))) return refused(call, "Unknown tool '" + clip(call.name()) + "'.");
        try {
            JsonNode a = JSON.readTree(call.argumentsJson() == null ? "{}" : call.argumentsJson());
            String repo = a.path("repo").asString(""), path = a.path("path").asString("");
            RepoTarget target = ctx.repos().stream().filter(r -> r.name().equals(repo)).findFirst().orElse(null);
            if (target == null) return refused(call, "Unknown repository '" + clip(repo) + "'.");
            if (call.name().equals("read_changed")) return new ToolLoop.Executed(numbered(copy(target).read(path), a.path("start_line").asInt(1), a.path("end_line").asInt(0)), false, trace(call, path, "read"), true);
            if (!allowed.getOrDefault(repo, Set.of()).contains(path)) {
                return refused(call, repo + ":" + path + " is not in the approved proposal, so it cannot be changed. Mention it in `notes` when you finish instead.");
            }
            Set<String> prot = approvedProtected.getOrDefault(repo, Set.of());
            if (call.name().equals("edit_file")) {
                String newText = a.path("new_text").asString("");
                copy(target).edit(path, a.path("old_text").asString(""), newText, prot);
                edits++;
                // Show the result here so the model does not spend a call (and re-send a whole file for the rest of the run) to check it.
                String shown = changedLines(copy(target).read(path), newText);
                return new ToolLoop.Executed("OK: edited " + path + "." + shown, false, trace(call, path, "edited"), shown.startsWith(" Now:")); // context lines come from the repository, so they are fenced as data
            }
            copy(target).createFile(path, a.path("content").asString(""), prot);
            edits++;
            return new ToolLoop.Executed("OK: created " + path + ".", false, trace(call, path, "created"), false);
        } catch (EditRejected e) {
            return refused(call, e.code() + ": " + e.getMessage());
        } catch (RuntimeException e) {
            return refused(call, "the arguments could not be understood; check them against the tool description");
        }
    }

    /** The diff of every repository that was changed; empty diffs are left out. */
    Map<String, WorkCopy.Diff> diffs() {
        Map<String, WorkCopy.Diff> out = new LinkedHashMap<>();
        open.forEach((repo, c) -> { WorkCopy.Diff d = c.diff(); if (!d.isEmpty()) out.put(repo, d); });
        return out;
    }

    private WorkCopy copy(RepoTarget target) {
        return open.computeIfAbsent(target.name(), n -> copies.open(ctx.runId() + "-" + n, sourceOf.apply(target), target.sha()));
    }

    @Override
    public void close() {
        new ArrayList<>(open.values()).forEach(WorkCopy::close);
        open.clear();
    }

    private static final int READ_LINES = 150;
    private static final int SNIPPET_CONTEXT = 2;
    private static final int SNIPPET_MAX_LINES = 20;

    /** Lines {@code from}..{@code to} (1-based; {@code to <= 0} means "as far as allowed") with line numbers, never more than {@link #READ_LINES}. */
    static String numbered(String text, int from, int to) {
        String[] lines = text.split("\n", -1);
        int start = Math.max(1, from);
        int end = Math.min(lines.length, to <= 0 ? start + READ_LINES - 1 : Math.min(to, start + READ_LINES - 1));
        StringBuilder sb = new StringBuilder();
        for (int i = start; i <= end; i++) sb.append(i).append(": ").append(lines[i - 1]).append('\n');
        if (end < lines.length) sb.append("… (lines ").append(end + 1).append("-").append(lines.length).append(" not shown; pass start_line and end_line)\n");
        return sb.toString();
    }

    /** The edited region with a little context, so an edit's reply is the check. Empty when the new text cannot be located (for example a deletion). */
    static String changedLines(String content, String newText) {
        if (newText == null || newText.isEmpty()) return " The text was removed.";
        int at = content.indexOf(newText);
        if (at < 0) return "";
        String[] lines = content.split("\n", -1);
        int firstLine = (int) content.substring(0, at).chars().filter(c -> c == '\n').count() + 1;
        int lastLine = firstLine + (int) newText.chars().filter(c -> c == '\n').count();
        int from = Math.max(1, firstLine - SNIPPET_CONTEXT);
        int to = Math.min(lines.length, Math.min(lastLine + SNIPPET_CONTEXT, from + SNIPPET_MAX_LINES - 1));
        StringBuilder sb = new StringBuilder(" Now:\n");
        for (int i = from; i <= to; i++) sb.append(i).append(": ").append(lines[i - 1]).append('\n');
        if (to < lastLine + SNIPPET_CONTEXT && to < lines.length) sb.append("… (edited region continues to line ").append(lastLine).append(")\n");
        return sb.toString();
    }

    private static ToolLoop.Executed refused(ToolCall call, String message) {
        return new ToolLoop.Executed("REFUSED: " + message, true, new ToolTrace(call.name(), clip(call.argumentsJson() == null ? "" : call.argumentsJson().replaceAll("\\s+", " ")), "refused"), false);
    }

    private static ToolTrace trace(ToolCall call, String path, String what) { return new ToolTrace(call.name(), path, what); }

    private static String clip(String s) { return s == null ? "" : s.length() <= 80 ? s : s.substring(0, 80) + "…"; }

}
