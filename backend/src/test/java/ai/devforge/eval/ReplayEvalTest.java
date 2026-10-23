package ai.devforge.eval;

import static org.assertj.core.api.Assertions.assertThat;

import ai.devforge.IntegrationTest;
import ai.devforge.repository.EvidenceVerifier;
import ai.devforge.repository.RepoReader;
import ai.devforge.repository.Repositories;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * AI-1 gate (replay). Every golden case is replayed through the production loop and judged by the production verifier. SYNTHETIC transcripts:
 * these prove the pipeline (policy, limits, evidence verification, trust labelling) is deterministic and safe; they do NOT measure how well a
 * real model investigates. Real-model quality is measured by LiveEvalTest when a key is available.
 */
class ReplayEvalTest extends IntegrationTest {
    @Autowired Repositories repositories;
    @Autowired RepoReader reader;
    @Autowired EvidenceVerifier verifier;
    @TempDir Path tmp;

    static Stream<EvalCase> cases() { return EvalCases.stream(); }

    private EvalRunner.Result runCase(EvalCase c) {
        var llm = new ReplayLlmClient(c.transcript());
        return new EvalRunner(repositories, reader, verifier).run(c, tmp.resolve(c.id()), llm, llm::requests);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    void everyGoldenCaseMeetsItsExpectations(EvalCase c) {
        EvalRunner.Result r = runCase(c);
        assertThat(r.violations()).as(c.id() + ": " + c.description()).isEmpty();
        assertThat(r.unsupportedClaims()).as("a claim without verified evidence must be impossible").isZero();
        assertThat(r.incomplete()).isFalse();
    }

    @Test
    void aggregateGatesAndReport() throws IOException {
        List<EvalRunner.Result> results = new ArrayList<>();
        for (EvalCase c : EvalCases.all()) results.add(new EvalRunner(repositories, reader, verifier).run(c, tmp.resolve("agg-" + c.id()), ((java.util.function.Supplier<ReplayLlmClient>) () -> new ReplayLlmClient(c.transcript())).get(), List::of));
        // (the aggregate re-runs without request capture; leakage is asserted per case above)
        long cases = results.size();
        double recall = results.stream().mapToDouble(EvalRunner.Result::fileRecall).average().orElse(0);
        int unsupported = results.stream().mapToInt(EvalRunner.Result::unsupportedClaims).sum();
        int downgrades = results.stream().mapToInt(EvalRunner.Result::downgrades).sum();
        double honestValidity = results.stream().filter(r -> !r.caseId().equals("dishonest-citations")).mapToDouble(EvalRunner.Result::citationValidity).average().orElse(1);

        assertThat(cases).as("at least five golden cases").isGreaterThanOrEqualTo(5);
        assertThat(unsupported).as("AI-1: no VERIFIED/SUPPORTED claim without server-verified evidence").isZero();
        assertThat(honestValidity).as("AI-1: citations from honest transcripts all verify").isEqualTo(1.0);
        assertThat(downgrades).as("the dishonest case really was caught").isGreaterThanOrEqualTo(2);
        assertThat(recall).as("file recall over golden cases").isGreaterThanOrEqualTo(0.99);

        JsonMapper json = JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build();
        Path out = Path.of("target/eval");
        Files.createDirectories(out);
        Files.writeString(out.resolve("replay-report.json"), json.writeValueAsString(java.util.Map.of("kind", "replay (synthetic transcripts: pipeline gate, not model quality)", "cases", results, "meanFileRecall", recall)));
    }
}
