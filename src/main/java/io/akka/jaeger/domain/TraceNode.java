package io.akka.jaeger.domain;

import java.util.List;

/** One span and its assembled children — SPEC-001 §2. */
public record TraceNode(Span span, List<TraceNode> children) {}
