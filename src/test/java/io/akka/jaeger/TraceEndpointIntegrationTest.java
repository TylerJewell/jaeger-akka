package io.akka.jaeger;

import static org.assertj.core.api.Assertions.assertThat;

import akka.javasdk.testkit.TestKitSupport;
import io.akka.jaeger.api.TraceEndpoint;
import io.akka.jaeger.api.TraceEndpoint.TraceView;
import io.akka.jaeger.domain.Warning;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The HTTP surface, end to end against a running runtime — the whole capability
 * driven the way a caller reaches it, since this port has no other interface
 * (see {@code gui/manifest.json}).
 */
public class TraceEndpointIntegrationTest extends TestKitSupport {

  private String trace() {
    return "t-" + UUID.randomUUID().toString().substring(0, 8);
  }

  private TraceView addSpan(
      String traceId, String spanId, String parentSpanId, long startMillis, long durationMillis) {
    return httpClient
        .POST("/traces/" + traceId + "/spans")
        .withRequestBody(
            new TraceEndpoint.SpanRequest(
                spanId, parentSpanId, "svc", "op", startMillis, durationMillis))
        .responseBodyAs(TraceView.class)
        .invoke()
        .body();
  }

  private TraceView get(String traceId) {
    return httpClient
        .GET("/traces/" + traceId)
        .responseBodyAs(TraceView.class)
        .invoke()
        .body();
  }

  @Test
  public void spansAddedOutOfOrderAssembleUnderTheirParent() {
    var traceId = trace();

    // child arrives before its parent — SPEC-001 rule 1
    addSpan(traceId, "child", "root", 5, 10);
    var afterParent = addSpan(traceId, "root", "", 0, 20);

    assertThat(afterParent.roots()).hasSize(1);
    assertThat(afterParent.roots().get(0).spanId()).isEqualTo("root");
    assertThat(afterParent.roots().get(0).children()).extracting(n -> n.spanId())
        .containsExactly("child");
    assertThat(afterParent.complete()).isTrue();

    var read = get(traceId);
    assertThat(read).isEqualTo(afterParent);
  }

  @Test
  public void aSpanWithNoParentInTheTraceSoFarIsAnIncompleteRoot() {
    var traceId = trace();
    var assembled = addSpan(traceId, "orphan", "never-arrives", 0, 10);

    assertThat(assembled.warnings())
        .containsExactly(new Warning("orphan", Warning.MISSING_PARENT));
    assertThat(assembled.complete()).isFalse();
  }

  @Test
  public void anEmptySpanIdIsRejectedWithABadRequest() {
    var traceId = trace();
    var response =
        httpClient
            .POST("/traces/" + traceId + "/spans")
            .withRequestBody(new TraceEndpoint.SpanRequest("", "", "svc", "op", 0, 0))
            .invoke();
    assertThat(response.httpResponse().status().intValue()).isEqualTo(400);
  }
}
