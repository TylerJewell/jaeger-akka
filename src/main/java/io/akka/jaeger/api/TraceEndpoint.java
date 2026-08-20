package io.akka.jaeger.api;

import akka.javasdk.annotations.Acl;
import akka.javasdk.annotations.http.Get;
import akka.javasdk.annotations.http.HttpEndpoint;
import akka.javasdk.annotations.http.Post;
import akka.javasdk.client.ComponentClient;
import akka.javasdk.http.HttpException;
import io.akka.jaeger.application.TraceAssemblyEntity;
import io.akka.jaeger.domain.AssembledTrace;
import io.akka.jaeger.domain.Span;
import io.akka.jaeger.domain.TraceNode;
import io.akka.jaeger.domain.Warning;
import java.util.List;

/**
 * Add spans to a trace, in any order, and read back the assembled tree —
 * SPEC-001. This is the port's own reachable surface: the source has no
 * equivalent single endpoint, since assembly there happens as a side effect of
 * a query-service read (question-log #8).
 */
@Acl(allow = @Acl.Matcher(principal = Acl.Principal.ALL))
@HttpEndpoint("/traces")
public class TraceEndpoint {

  private final ComponentClient componentClient;

  public TraceEndpoint(ComponentClient componentClient) {
    this.componentClient = componentClient;
  }

  public record SpanRequest(
      String spanId,
      String parentSpanId,
      String serviceName,
      String operationName,
      long startMillis,
      long durationMillis) {}

  public record NodeView(
      String spanId,
      String parentSpanId,
      String serviceName,
      String operationName,
      long startMillis,
      long durationMillis,
      List<NodeView> children) {}

  public record TraceView(
      String traceId, List<NodeView> roots, List<Warning> warnings, boolean complete) {}

  @Post("/{traceId}/spans")
  public TraceView addSpan(String traceId, SpanRequest request) {
    if (request.spanId() == null || request.spanId().isEmpty()) {
      throw HttpException.badRequest("spanId must not be empty");
    }
    var span =
        new Span(
            request.spanId(),
            request.parentSpanId() == null ? "" : request.parentSpanId(),
            request.serviceName(),
            request.operationName(),
            request.startMillis(),
            request.durationMillis());
    return toApi(trace(traceId).method(TraceAssemblyEntity::addSpan).invoke(span));
  }

  @Get("/{traceId}")
  public TraceView get(String traceId) {
    return toApi(trace(traceId).method(TraceAssemblyEntity::get).invoke());
  }

  private static TraceView toApi(AssembledTrace trace) {
    return new TraceView(
        trace.traceId(), trace.roots().stream().map(TraceEndpoint::toApi).toList(),
        trace.warnings(), trace.complete());
  }

  private static NodeView toApi(TraceNode node) {
    var span = node.span();
    return new NodeView(
        span.spanId(),
        span.parentSpanId(),
        span.serviceName(),
        span.operationName(),
        span.startMillis(),
        span.durationMillis(),
        node.children().stream().map(TraceEndpoint::toApi).toList());
  }

  private akka.javasdk.client.EventSourcedEntityClient trace(String traceId) {
    return componentClient.forEventSourcedEntity(traceId);
  }
}
