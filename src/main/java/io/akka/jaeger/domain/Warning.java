package io.akka.jaeger.domain;

/**
 * A span that did not cleanly attach as a tree node during assembly, and why —
 * SPEC-001 §2, mirroring {@code jptrace.AddWarnings} (question-log #3, #5, #6).
 */
public record Warning(String spanId, String reason) {
  public static final String MISSING_PARENT = "missing parent";
  public static final String DUPLICATE_SPAN_ID = "duplicate span id";
  public static final String CYCLIC_PARENT_REFERENCE = "cyclic parent reference";
}
