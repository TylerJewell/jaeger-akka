package io.akka.jaeger.application;

import akka.javasdk.annotations.Component;
import akka.javasdk.eventsourcedentity.EventSourcedEntity;
import akka.javasdk.eventsourcedentity.EventSourcedEntityContext;
import io.akka.jaeger.domain.AssembledTrace;
import io.akka.jaeger.domain.Span;
import io.akka.jaeger.domain.SpanEvent;
import io.akka.jaeger.domain.TraceState;

/**
 * One trace's assembly — SPEC-001. The entity id is the trace id. Spans can
 * arrive in any order, including duplicates; every rule lives in
 * {@link TraceState}, tested without a runtime in {@code TraceStateTest}.
 */
@Component(id = "trace-assembly")
public class TraceAssemblyEntity extends EventSourcedEntity<TraceState, SpanEvent> {

  private final String traceId;

  public TraceAssemblyEntity(EventSourcedEntityContext context) {
    this.traceId = context.entityId();
  }

  @Override
  public TraceState emptyState() {
    return TraceState.empty(traceId);
  }

  public Effect<AssembledTrace> addSpan(Span span) {
    return effects()
        .persist(currentState().planReceive(span))
        .thenReply(state -> state.assemble());
  }

  public ReadOnlyEffect<AssembledTrace> get() {
    return effects().reply(currentState().assemble());
  }

  @Override
  public TraceState applyEvent(SpanEvent event) {
    return currentState().onEvent(event);
  }
}
