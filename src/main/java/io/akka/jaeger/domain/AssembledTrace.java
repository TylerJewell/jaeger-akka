package io.akka.jaeger.domain;

import java.util.List;

/**
 * The derived, read-only view of a trace: SPEC-001 §2. More than one root means at
 * least one span is missing its parent or sits in a parent-reference cycle.
 * {@code complete} is this port's own concept — question-log #9 found no name for
 * it anywhere in the source.
 */
public record AssembledTrace(
    String traceId, List<TraceNode> roots, List<Warning> warnings, boolean complete) {}
