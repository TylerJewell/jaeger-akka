# jaeger-akka

Folds spans that arrive in any order into a parent/child tree, and says which parts of
that tree are missing a parent.

A port of [jaegertracing/jaeger](https://github.com/jaegertracing/jaeger) onto **Akka**,
built with **Akka Specify**.

---

## Where it came from

jaegertracing/jaeger is a distributed-tracing system: it collects spans — one record per
piece of work a request does, tagged with which piece of work called it — and lets
someone look up everything a single request did across every service it touched. It was
ported to derive a specification format precise enough to regenerate a system on a
different stack — the port is the vehicle, the specification is the deliverable.

Only one piece of jaeger is rebuilt here: the part that takes whatever spans have arrived
for one request so far and turns them into a tree, including deciding what to do about a
span whose parent has not arrived, or never will. The specifications the port was
generated from are in
[TylerJewell/akka-specify-harness](https://github.com/TylerJewell/akka-specify-harness)
under `jaeger-port/`.

---

## jaegertracing/jaeger → this port

📉 39 Go lines → **51 Java lines**<br>
📁 1 file → **8 files**<br>
⚡ 1,099 nanoseconds → **1,293 nanoseconds** per fold<br>
🧪 13 table cases → **20 tests**<br>
🔁 0 rules broken on purpose → **5 of 5 rules broken and caught**<br>
🎯 5 of 6 shared cases give the same answer<br>
🕳️ 1 unflagged gap (a mutual-parent cycle) → **0**

Full method and the numbers that did *not* make this list: [`bench/REPORT.md`](https://github.com/TylerJewell/akka-specify-harness/blob/main/jaeger-port/bench/REPORT.md).

---

## What it took to build

⏱️ **0.7 hours** from the first command to the published repository, **0.7** of them active<br>
💬 **402** exchanges with the model<br>
✍️ **252,342** tokens written by the model, **79,077,581** counting everything sent and re-sent<br>
🙋 **0** questions to a human<br>
🧪 **20** tests

```bash
python toolkit/tokens.py --port jaeger    # turns, tokens, elapsed and active time
```

The record of every question, and where the time went, is in
[`port-log/`](https://github.com/TylerJewell/akka-specify-harness/tree/main/port-log).

---

## What it does

A request's spans do not all arrive together, and nothing enforces that a parent arrives
before its children. Given whatever has arrived for one request so far, this answers:
what does the tree look like, and which parts of it are missing something.

From the specification:

- **The tree comes out the same no matter what order the spans arrived in.** Every span
  is placed by looking up its own declared parent, not by where it sits in the list it
  arrived in — so a child that arrives before its parent still ends up under it.
- **A span whose parent has not arrived becomes its own root, and says so.** It is never
  dropped and never attached to the wrong place; the caller can see exactly which part of
  the tree is incomplete and why.
- **Two spans each missing their parent stay two separate problems.** They are never
  merged into one.
- **The first copy of a repeated span id wins; every later copy is marked and set aside.**
  Nothing is lost — every span that arrived is still on record — but only the first copy
  shapes the tree.
- **Two spans that name each other as parent are still shown, not hidden.** Each becomes
  its own root, and each is marked with the reason.
- **A request counts as fully assembled only when it has exactly one root and nothing has
  been marked.** The moment either of those stops being true, it is incomplete, and that
  can change from one span to the next as more of them arrive.

Nothing here calls a language model. The work is a fold over records already received;
what produced those spans, and how they were sent here, belongs to a different part of
jaeger.

---

## Design decisions

**Every span that arrives is kept forever, whether or not it ends up in the tree.** A
copy of a span id that arrives late is marked and set aside rather than thrown away,
because a caller asking "what did we actually receive for this request" deserves the true
answer, not the answer after the tree-building rule has already decided what to keep.

**A tree is recomputed from what has arrived, not carried forward and patched.** Every
span sent so far is folded from scratch each time the tree is asked for, so the answer
can never drift out of step with what was actually received.

**Two spans naming each other as parent are shown, not hidden.** jaeger's own code
quietly leaves such spans out of the tree with no explanation attached anywhere. Here they
are shown the same way a missing parent is shown, because a request that says
"incomplete, and here is why" is more useful than one that quietly loses two spans and
says nothing.

**One entity per request.** Everything that must be assembled together — every span for
one request — lives in one place, and nothing about one request is ever read while
building the tree for another.

---

## Running it — the short path

You do not need Java, Maven, or the Akka CLI installed. Akka Specify installs them for you.

**1. Install Akka Specify** in Claude Code:

```
/plugin marketplace add akka/ai-marketplace
/plugin install akka@akka-ai-marketplace
```

Restart Claude Code when it asks.

**2. Give it this prompt:**

> Clone https://github.com/TylerJewell/jaeger-akka into a new directory and open it.
> Then run /akka:setup to install everything this project needs, and /akka:build to
> compile it, run the tests, and start it locally.

**3. Open** http://localhost:9024.

---

## Running it — the developer path

### Requirements

- Java 21 or newer
- Maven 3.9 or newer
- An Akka download token — run `akka code token` once

### Start the service

```bash
mvn compile
akka local run
```

The service starts on **port 9024**.

### Send some spans

```bash
# a child arrives before its parent
curl -X POST localhost:9024/traces/request-1/spans -H 'Content-Type: application/json' \
  -d '{"spanId":"child","parentSpanId":"root","serviceName":"checkout","operationName":"charge","startMillis":5,"durationMillis":10}'

curl -X POST localhost:9024/traces/request-1/spans -H 'Content-Type: application/json' \
  -d '{"spanId":"root","parentSpanId":"","serviceName":"checkout","operationName":"handle","startMillis":0,"durationMillis":20}'
# -> the child is already under "root" in the reply, even though it arrived first

curl localhost:9024/traces/request-1
```

---

## Configuration

There are no environment variables. The one setting is the port it listens on, written in
`src/main/resources/application.conf`:

```
akka.javasdk.dev-mode.http-port = 9024
```

---

## Where it differs from jaegertracing/jaeger

Everything not listed here behaves the same way on purpose, including the parts that look
like mistakes.

- **Two spans that name each other as parent.** jaeger builds its tree by walking down
  from every span that has no parent or an unreachable one; a pair that names only each
  other never reaches either starting point, so both spans are silently left out of the
  tree, with no warning attached anywhere. This port shows both spans instead, each as its
  own root with a reason attached, because a request that says "here is what is wrong"
  is more useful than one that quietly loses two spans.
- **Clock-skew timestamp correction, attribute sorting, and hash-based whole-span
  deduplication.** jaeger runs all of these over the same tree this port builds, in the
  same pass. None of them are rebuilt here — the wording of what a warning says, and
  reordering timestamps between spans from different hosts, are both a different job from
  deciding what the tree looks like.
- **Whether "complete" is a thing a caller can ask for.** jaeger never names this
  concept anywhere in its own code. This port answers it directly: a request is complete
  once it has exactly one root and nothing has been marked, and incomplete otherwise.
- **How long a request's spans are kept.** jaeger's spans live in whatever storage
  backend is configured, with its own retention policy. Here, everything sent for one
  request is kept for as long as that request's entity exists, with no eviction — `not
  checked` against any particular volume of spans, because no request that kept receiving
  spans indefinitely was tried against either side.
- **The wording of what a warning says.** jaeger's two warnings are full sentences
  naming the clock-skew adjustment this port does not implement. Here the same two
  conditions are named in a few words each, and a third condition — two spans naming each
  other as parent — has a warning of its own that jaeger has no wording for at all.

---

## Licence

jaegertracing/jaeger is Apache License 2.0, © The Jaeger Authors. This port reimplements
the behaviour without copied source; see [`ACKNOWLEDGEMENTS.md`](ACKNOWLEDGEMENTS.md).
