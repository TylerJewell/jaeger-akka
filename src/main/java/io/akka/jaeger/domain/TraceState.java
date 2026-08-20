package io.akka.jaeger.domain;

import io.akka.jaeger.domain.SpanEvent.SpanReceived;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Every span received for one trace, in receipt order — the event-sourced log
 * (SPEC-001 §2). Nothing is ever removed, including later duplicates.
 */
public record TraceState(String traceId, List<Span> received) {

  public static TraceState empty(String traceId) {
    return new TraceState(traceId, List.of());
  }

  /** Recording an arrival is never rejected — the log is a faithful record of what arrived. */
  public SpanEvent planReceive(Span span) {
    return new SpanReceived(span);
  }

  public TraceState onEvent(SpanEvent event) {
    if (event instanceof SpanReceived(Span span)) {
      var next = new ArrayList<>(received);
      next.add(span);
      return new TraceState(traceId, List.copyOf(next));
    }
    throw new IllegalStateException("unknown event: " + event);
  }

  /**
   * Folds {@link #received} into a parent/child tree — SPEC-001 §3 rules 1-6. The
   * result is deterministic regardless of receipt order: spans are grouped by ID
   * before any tree is built (rule 1), and the output is sorted so that receipt
   * order never leaks into the shape of the answer.
   */
  public AssembledTrace assemble() {
    Map<String, Span> byId = new LinkedHashMap<>();
    List<Warning> warnings = new ArrayList<>();
    for (Span span : received) {
      if (byId.containsKey(span.spanId())) {
        warnings.add(new Warning(span.spanId(), Warning.DUPLICATE_SPAN_ID));
      } else {
        byId.put(span.spanId(), span);
      }
    }

    Map<String, List<String>> childIds = new LinkedHashMap<>();
    List<String> declaredRootIds = new ArrayList<>();
    for (Span span : byId.values()) {
      if (!span.hasParent()) {
        declaredRootIds.add(span.spanId());
      } else if (byId.containsKey(span.parentSpanId())) {
        childIds.computeIfAbsent(span.parentSpanId(), k -> new ArrayList<>()).add(span.spanId());
      } else {
        warnings.add(new Warning(span.spanId(), Warning.MISSING_PARENT));
        declaredRootIds.add(span.spanId());
      }
    }

    Set<String> visited = new LinkedHashSet<>();
    List<TraceNode> roots = new ArrayList<>();
    for (String rootId : declaredRootIds) {
      roots.add(buildNode(rootId, byId, childIds, visited));
    }
    // Every remaining span has a parent that is itself present in byId, yet was
    // never reached walking down from a declared root — the only way that
    // happens in a graph where each span has at most one parent is a cycle
    // (question-log #6). Each member of the cycle becomes its own root.
    for (String id : byId.keySet()) {
      if (!visited.contains(id)) {
        warnings.add(new Warning(id, Warning.CYCLIC_PARENT_REFERENCE));
        roots.add(new TraceNode(byId.get(id), List.of()));
        visited.add(id);
      }
    }

    roots.sort(Comparator.comparing(n -> n.span().spanId()));
    warnings.sort(Comparator.comparing(Warning::spanId).thenComparing(Warning::reason));
    boolean complete = roots.size() == 1 && warnings.isEmpty();
    return new AssembledTrace(traceId, roots, warnings, complete);
  }

  private static TraceNode buildNode(
      String id, Map<String, Span> byId, Map<String, List<String>> childIds, Set<String> visited) {
    visited.add(id);
    List<TraceNode> children = new ArrayList<>();
    for (String childId : childIds.getOrDefault(id, List.of())) {
      if (!visited.contains(childId)) {
        children.add(buildNode(childId, byId, childIds, visited));
      }
    }
    children.sort(Comparator.comparing(n -> n.span().spanId()));
    return new TraceNode(byId.get(id), children);
  }
}
