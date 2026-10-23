package ai.devforge.eval;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import tools.jackson.databind.json.JsonMapper;

final class EvalCases {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Path DIR = Path.of("src/test/resources/eval/cases");

    private EvalCases() {}

    static List<EvalCase> all() {
        try (Stream<Path> files = Files.list(DIR)) {
            return files.filter(p -> p.toString().endsWith(".json")).sorted().map(EvalCases::read).toList();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    static Stream<EvalCase> stream() { return all().stream(); }

    private static EvalCase read(Path p) { return JSON.readValue(p.toFile(), EvalCase.class); }
}
