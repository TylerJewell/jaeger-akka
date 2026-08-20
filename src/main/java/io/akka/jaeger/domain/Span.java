package io.akka.jaeger.domain;

/**
 * One span as received, before assembly. {@code parentSpanId} empty means "no
 * parent" — SPEC-001 §2, mirroring jaeger's use of an empty {@code SpanID} for
 * the same purpose ({@code clockskew.go:124}).
 */
public record Span(
    String spanId,
    String parentSpanId,
    String serviceName,
    String operationName,
    long startMillis,
    long durationMillis) {

  public Span {
    if (spanId == null || spanId.isEmpty()) {
      throw new IllegalArgumentException("spanId must not be empty");
    }
    if (parentSpanId == null) {
      parentSpanId = "";
    }
  }

  public boolean hasParent() {
    return !parentSpanId.isEmpty();
  }
}
