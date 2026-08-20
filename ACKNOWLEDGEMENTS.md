# Acknowledgements

This project is a port of **[jaegertracing/jaeger](https://github.com/jaegertracing/jaeger)**,
read and run at commit `8498073` (2026-08-20).

## Licence

jaeger is **Apache License 2.0**, © The Jaeger Authors. A copy of that licence is included as
`LICENSE-jaeger`, which Apache-2.0 requires of any work carrying its material, along with the
notice of what was changed that section 4(b) asks for — this whole file is that notice.

## What was copied

**No source was copied.** No Go file, fragment or expression from jaeger appears here; every
file in `src/` was written for this project.

Two things were taken across deliberately, and both are values a caller reads or compares
against rather than code:

- **The three warning reasons and their wording** — jaeger's `warningMissingParentSpanID`
  (`"parent span ID=%s is not in the trace; skipping clock skew adjustment"`) and
  `warningDuplicateSpanID` (`"duplicate span IDs; skipping clock skew adjustment"`) name the
  same two conditions this port's `Warning.MISSING_PARENT` and `Warning.DUPLICATE_SPAN_ID`
  detect; this port's own shorter phrasing is used instead of jaeger's sentence, since jaeger's
  wording names the clock-skew adjustment this port does not implement (SPEC-001 §1, out of
  scope). `Warning.CYCLIC_PARENT_REFERENCE` names a condition jaeger's own code does not
  detect at all (question-log #6) and has no source wording to take.
- **The empty-parent-span-ID sentinel** — jaeger uses an empty `SpanID` to mean "no parent"
  (`clockskew.go:124`); this port uses an empty string for the same purpose.

## What is derived

The behaviour is. Every rule in `jaeger-port/specs/SPEC-001-jaeger.md` was established by
reading `internal/extension/jaegerquery/internal/adjuster/clockskew.go` and its own test
suite, then running both that suite and a small number of additional probes against the real
package to check claims the existing tests did not cover (order independence across
`ResourceSpans` blocks, two independent orphans, and a mutual-parent cycle). The record of
what was checked and how is `jaeger-port/docs/question-log.md`.

One rule was deliberately **not** taken from the source: jaeger silently excludes spans caught
in a mutual-parent cycle from the assembled tree, with no warning. This port surfaces every
such span as its own root with a `cyclic parent reference` warning instead. The reasoning is
in `jaeger-port/specs/SPEC-001-jaeger.md` §4 and in `README.md` under
`Where it differs from jaeger`.

## Also used

- **Akka** (Akka SDK for Java, BSL 1.1) — the platform this port is built on.
- **Go** (`go test`) was used to run jaeger's own adjuster and storage test suites, and to run
  the throwaway probes described above; nothing from that toolchain was copied.
