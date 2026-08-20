package io.akka.jaeger.domain;

import akka.javasdk.annotations.TypeName;

/** Everything received about a trace is kept, including later duplicates — SPEC-001 §2. */
public sealed interface SpanEvent {
  @TypeName("span-received")
  record SpanReceived(Span span) implements SpanEvent {}
}
