package io.akka.jaeger.application;

import static org.assertj.core.api.Assertions.assertThat;

import akka.javasdk.testkit.EventSourcedTestKit;
import io.akka.jaeger.domain.Span;
import io.akka.jaeger.domain.SpanEvent;
import io.akka.jaeger.domain.TraceState;
import io.akka.jaeger.domain.Warning;
import org.junit.jupiter.api.Test;

/**
 * SPEC-001 against the entity: that adding a span persists exactly one event and
 * replies with the freshly assembled trace, and that {@code get} re-derives the
 * same answer from what has been persisted so far without adding anything.
 */
public class TraceAssemblyEntityTest {

  private EventSourcedTestKit<TraceState, SpanEvent, TraceAssemblyEntity> trace() {
    return EventSourcedTestKit.of("trace-1", TraceAssemblyEntity::new);
  }

  @Test
  public void addingASpanPersistsOneEventAndRepliesWithTheAssembledTrace() {
    var kit = trace();
    var result =
        kit.method(TraceAssemblyEntity::addSpan)
            .invoke(new Span("root", "", "svc", "op", 0, 10));

    assertThat(result.getAllEvents()).hasSize(1).first().isInstanceOf(SpanEvent.SpanReceived.class);
    var reply = result.getReply();
    assertThat(reply.roots()).hasSize(1);
    assertThat(reply.complete()).isTrue();
  }

  @Test
  public void spansArrivingChildFirstStillAssembleUnderTheParent() {
    var kit = trace();
    kit.method(TraceAssemblyEntity::addSpan).invoke(new Span("child", "root", "svc", "op", 5, 10));
    var reply =
        kit.method(TraceAssemblyEntity::addSpan)
            .invoke(new Span("root", "", "svc", "op", 0, 20))
            .getReply();

    assertThat(reply.roots()).hasSize(1);
    assertThat(reply.roots().get(0).span().spanId()).isEqualTo("root");
    assertThat(reply.roots().get(0).children()).extracting(n -> n.span().spanId())
        .containsExactly("child");
    assertThat(reply.complete()).isTrue();
  }

  @Test
  public void getReadsBackTheSameAssemblyWithoutAddingAnything() {
    var kit = trace();
    kit.method(TraceAssemblyEntity::addSpan).invoke(new Span("orphan", "missing", "svc", "op", 0, 10));

    var view = kit.method(TraceAssemblyEntity::get).invoke().getReply();
    assertThat(view.warnings()).containsExactly(new Warning("orphan", Warning.MISSING_PARENT));
    assertThat(view.complete()).isFalse();

    var viewAgain = kit.method(TraceAssemblyEntity::get).invoke().getReply();
    assertThat(viewAgain).isEqualTo(view);
  }
}
