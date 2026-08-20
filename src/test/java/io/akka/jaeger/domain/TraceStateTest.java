package io.akka.jaeger.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** SPEC-001 §3 rules 1-6, checked without a runtime. */
class TraceStateTest {

  private static Span span(String id, String parent) {
    return new Span(id, parent, "svc", "op", 0, 10);
  }

  private static TraceState receive(TraceState state, Span span) {
    return state.onEvent(state.planReceive(span));
  }

  // Rule 1: same spans, two receipt orders, identical assembled result. A second,
  // unrelated root (z) is included so that the top-level root ordering itself is
  // exercised by receipt order, not just the parent/child pairing within one tree.
  @Test
  void orderIndependence() {
    var a = span("a", "");
    var b = span("b", "a");
    var c = span("c", "b");
    var z = span("z", "");

    var forward = TraceState.empty("t1");
    forward = receive(forward, a);
    forward = receive(forward, b);
    forward = receive(forward, c);
    forward = receive(forward, z);

    var reverse = TraceState.empty("t1");
    reverse = receive(reverse, z);
    reverse = receive(reverse, c);
    reverse = receive(reverse, b);
    reverse = receive(reverse, a);

    assertThat(forward.assemble()).isEqualTo(reverse.assemble());
  }

  // Rule 2: missing parent -> its own root, plus a warning.
  @Test
  void missingParentBecomesOwnRootWithWarning() {
    var state = receive(TraceState.empty("t1"), span("x", "does-not-exist"));
    var assembled = state.assemble();

    assertThat(assembled.roots()).hasSize(1);
    assertThat(assembled.roots().get(0).span().spanId()).isEqualTo("x");
    assertThat(assembled.warnings())
        .containsExactly(new Warning("x", Warning.MISSING_PARENT));
    assertThat(assembled.complete()).isFalse();
  }

  // Rule 2 (empty parent is not an error): an empty parentSpanId is just a normal root.
  @Test
  void emptyParentIsARootWithoutWarning() {
    var state = receive(TraceState.empty("t1"), span("root", ""));
    var assembled = state.assemble();

    assertThat(assembled.roots()).hasSize(1);
    assertThat(assembled.warnings()).isEmpty();
    assertThat(assembled.complete()).isTrue();
  }

  // Rule 3: two independently orphaned spans never merge into one root.
  @Test
  void twoIndependentOrphansStayIndependent() {
    var state = TraceState.empty("t1");
    state = receive(state, span("x", "missing-1"));
    state = receive(state, span("y", "missing-2"));
    var assembled = state.assemble();

    assertThat(assembled.roots()).hasSize(2);
    assertThat(assembled.warnings())
        .containsExactlyInAnyOrder(
            new Warning("x", Warning.MISSING_PARENT), new Warning("y", Warning.MISSING_PARENT));
  }

  // Rule 4: first-received span with an id wins the tree node; later ones are excluded and warned.
  @Test
  void duplicateSpanIdExcludesLaterOnes() {
    var first = new Span("dup", "", "svc-a", "op-a", 0, 10);
    var second = new Span("dup", "", "svc-b", "op-b", 5, 20);

    var state = TraceState.empty("t1");
    state = receive(state, first);
    state = receive(state, second);
    var assembled = state.assemble();

    assertThat(assembled.roots()).hasSize(1);
    assertThat(assembled.roots().get(0).span()).isEqualTo(first);
    assertThat(assembled.warnings())
        .containsExactly(new Warning("dup", Warning.DUPLICATE_SPAN_ID));
    assertThat(state.received()).containsExactly(first, second);
  }

  // Rule 5: a mutual-parent cycle exposes every member as its own root, with a warning each —
  // the deliberate divergence from the source recorded in SPEC-001 §4.
  @Test
  void mutualParentCycleExposesEachMemberAsOwnRoot() {
    var state = TraceState.empty("t1");
    state = receive(state, span("p", "q"));
    state = receive(state, span("q", "p"));
    var assembled = state.assemble();

    assertThat(assembled.roots()).hasSize(2);
    assertThat(assembled.roots()).allSatisfy(node -> assertThat(node.children()).isEmpty());
    assertThat(assembled.warnings())
        .containsExactlyInAnyOrder(
            new Warning("p", Warning.CYCLIC_PARENT_REFERENCE),
            new Warning("q", Warning.CYCLIC_PARENT_REFERENCE));
  }

  // Rule 6: complete iff exactly one root and zero warnings.
  @Test
  void completeOnlyWithOneRootAndNoWarnings() {
    var state = TraceState.empty("t1");
    state = receive(state, span("root", ""));
    state = receive(state, span("child", "root"));
    assertThat(state.assemble().complete()).isTrue();

    var incomplete = receive(state, span("orphan", "missing"));
    assertThat(incomplete.assemble().complete()).isFalse();
  }

  @Test
  void receivedLogRetainsEverythingInReceiptOrder() {
    var a = span("a", "");
    var b = span("b", "a");
    var state = receive(receive(TraceState.empty("t1"), a), b);
    assertThat(state.received()).containsExactly(a, b);
  }

  @Test
  void emptyTraceAssemblesToNoRootsAndIsIncomplete() {
    var assembled = TraceState.empty("t1").assemble();
    assertThat(assembled.roots()).isEmpty();
    assertThat(assembled.warnings()).isEmpty();
    assertThat(assembled.complete()).isFalse();
  }

  @Test
  void treeShapeNestsChildrenUnderTheirParent() {
    var state = TraceState.empty("t1");
    state = receive(state, span("root", ""));
    state = receive(state, span("child-1", "root"));
    state = receive(state, span("child-2", "root"));
    state = receive(state, span("grandchild", "child-1"));
    var assembled = state.assemble();

    assertThat(assembled.roots()).hasSize(1);
    var root = assembled.roots().get(0);
    assertThat(root.span().spanId()).isEqualTo("root");
    assertThat(root.children()).extracting(n -> n.span().spanId())
        .containsExactly("child-1", "child-2");
    assertThat(root.children().get(0).children()).extracting(n -> n.span().spanId())
        .containsExactly("grandchild");
  }

  @Test
  void plannedEventCarriesTheReceivedSpan() {
    var s = span("a", "");
    var event = TraceState.empty("t1").planReceive(s);
    assertThat(event).isInstanceOf(SpanEvent.SpanReceived.class);
    assertThat(((SpanEvent.SpanReceived) event).span()).isEqualTo(s);
  }

  @Test
  void spanRejectsEmptySpanId() {
    org.junit.jupiter.api.Assertions.assertThrows(
        IllegalArgumentException.class, () -> new Span("", "", "svc", "op", 0, 0));
  }

  @Test
  void spanTreatsNullParentAsNoParent() {
    var s = new Span("a", null, "svc", "op", 0, 0);
    assertThat(s.hasParent()).isFalse();
    assertThat(s.parentSpanId()).isEmpty();
  }
}
