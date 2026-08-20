package io.akka.jaeger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.akka.jaeger.domain.Span;
import io.akka.jaeger.domain.TraceState;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The port's half of the differential in {@code jaeger-port/bench/}.
 *
 * <p>Runs the same six cases as {@code jaeger-port/bench/run_source.py}, read from the
 * same {@code cases.json} so neither side can drift from the other, and writes
 * {@code port-answers.json} next to the source's. {@code bench/compare.py} puts the two
 * side by side; comparing speed before the answers agree would measure nothing.
 *
 * <p>A plain unit test rather than a {@code TestKitSupport} integration test: the domain
 * fold under measurement (SPEC-001 §3) has no Akka dependency, so this drives the real
 * {@link TraceState#assemble()} directly, at the same layer {@code TraceStateTest} checks
 * SPEC-001's rules against.
 */
public class BenchAnswersTest {

  private static final Path BENCH = Path.of("..", "jaeger-port", "bench");
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final int TIMED_ITERATIONS = 20_000;
  private static final int WARMUP_ITERATIONS = 2_000;

  @Test
  public void runsTheSharedCasesAndWritesThePortsAnswers() throws IOException {
    var cases = (ArrayNode) JSON.readTree(Files.readString(BENCH.resolve("cases.json"))).get("cases");

    var results = JSON.createArrayNode();
    List<Span> chainWorkload = null;
    for (JsonNode c : cases) {
      var spans = readSpans(c);
      if (c.get("name").asText().equals("chain")) {
        chainWorkload = spans;
      }
      var assembled = new TraceState("bench", spans).assemble();

      var roots = JSON.createArrayNode();
      assembled.roots().stream().map(n -> n.span().spanId()).sorted().forEach(roots::add);

      var warnings = JSON.createArrayNode();
      assembled.warnings().stream()
          .map(w -> w.spanId() + ":" + w.reason().replace(' ', '_'))
          .sorted()
          .forEach(warnings::add);

      ObjectNode row = JSON.createObjectNode();
      row.put("name", c.get("name").asText());
      row.set("roots", roots);
      row.set("warnings", warnings);
      results.add(row);
    }

    // warm up the JIT before timing the same "chain" workload the source side times.
    for (int i = 0; i < WARMUP_ITERATIONS; i++) {
      new TraceState("bench", chainWorkload).assemble();
    }
    long start = System.nanoTime();
    for (int i = 0; i < TIMED_ITERATIONS; i++) {
      new TraceState("bench", chainWorkload).assemble();
    }
    long nsPerOp = (System.nanoTime() - start) / TIMED_ITERATIONS;

    ObjectNode out = JSON.createObjectNode();
    out.put("runtime", "Java (mvn test)");
    out.set("cases", results);
    out.put("ns_per_op", nsPerOp);
    Files.writeString(BENCH.resolve("port-answers.json"), out.toPrettyString() + "\n");
  }

  private static List<Span> readSpans(JsonNode caseNode) {
    List<Span> spans = new ArrayList<>();
    for (JsonNode s : caseNode.get("spans")) {
      spans.add(
          new Span(
              s.get("id").asText(),
              s.get("parent").asText(),
              "svc",
              "op",
              s.get("startMs").asLong(),
              s.get("durationMs").asLong()));
    }
    return spans;
  }
}
