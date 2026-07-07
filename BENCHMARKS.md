# JVM vs Native Image Benchmarks

Two benchmarks compare running the identical AOT-compiled Clojure program on
the JVM and as a GraalVM native image, both calling C through coffi. Source
and harness live in `examples/benchmarks/`.

```sh
docker build -f Dockerfile.bench -t coffi-bench . && docker run --rm coffi-bench
```

The native-image build happens inside `docker run`, so tuning knobs are
runtime env vars:

| knob | effect |
|---|---|
| `-e EXTRA_NATIVE_OPTS=...` | extra `native-image` flags, e.g. `--gc=G1` (Oracle GraalVM) or `-J-Dcoffi.ffi.eager-native-image-handles=true` (GraalVM 25.1+ — see the eager-handles section) |
| `-e PGO=1` | profile-guided optimization: instrumented build → profiling runs of every mode → optimizing build (Oracle GraalVM only) |
| `--build-arg BASE_IMAGE=container-registry.oracle.com/graalvm/native-image:25` | Oracle GraalVM instead of the community edition (build-time arg) |

## Methodology

- **Single-shot process runs, reported both with and without startup.**
  Wall time is measured around the whole process; each run also prints
  `work_ms`, in-process time around the workload itself, so wall − work is
  startup. The harness prints two result tables: `RESULTS` (wall, what a
  CLI user experiences) and `RESULTS-WORK` (sans startup — the comparison
  for long-running JVMs, where startup is amortized; note work still
  includes JIT warmup, so it slightly understates a fully warm JVM). Each
  process reports its own peak RSS (`VmHWM`) and consumed CPU from
  `/proc/self` — both modes measured identically.
- **Same bytecode, same C.** Both modes run the same direct-linked AOT
  classes (direct linking is the standard Clojure native-image
  configuration; without it, Var indirection handicaps specifically the
  Graal AOT side, since C2 devirtualizes it with runtime profiles). The
  shared library is the same gcc `-O2` build in both modes.
- **Pinned 4 GB max heap on both modes.** The mem benchmarks are GC
  benchmarks; uncapped, each runtime's ergonomics would pick a different
  ceiling and the benchmark would measure that instead.
- **Per-call figures are the best of 5 in-process rounds** (same total call
  count, split), so early rounds absorb JIT warmup and the reported number
  is steady state. Whole-process wall times still include warmup, by design.
- **A jextract-pattern control** (`BenchStub.java`: a downcall handle in a
  `static final` field, `invokeExact` with exact types from plain Java)
  separates coffi's overhead from the platform's.
- Environment for the tables below: Linux x64 container, Docker on Windows;
  GraalVM CE 25.0.2 for the defaults tables, Oracle GraalVM 25.0.3 for the
  tuned table, GraalVM CE 25.1.3 (`25i1`) for the eager-handles table.
  Each table set comes from its own session with its own calibration
  score — compare across them via `ref` values, within a table directly.
  (Note the "JVM" in each table is the GraalVM distribution's own `java`
  from the same image.)

## Calibrating to your machine

Absolute numbers only hold on the machine that produced them, so the repo
ships a fixed zero-dependency reference workload that scores any machine on
the two axes the benchmarks stress:

```sh
docker build -f Dockerfile.reference -t coffi-reference . && docker run --rm coffi-reference
```

It prints a cpu time, a mem time, and `score` — their geometric mean, the
machine's single calibration number. The machine behind the tables scores
**150–162 depending on the session** (Docker-on-Windows variance; each
table below states its session's score). To estimate an absolute number on
your machine, multiply it by your score over the session score. Every
`Dockerfile.bench` run prints its own score, and each `RESULT` line
reports `ref=` — time divided by that score — so results pasted from
different machines and sessions are directly comparable. Ratios *within*
one table (JVM vs native, coffi vs jextract) carry over as-is.

## Results (community edition, defaults)

Session score 160. `work` rows are in-process time sans startup — the
comparison for long-running JVMs; startup itself is ~0.6 s for the JVM and
~5–40 ms for native, in every mode.

**CPU-limited** — 1M small FFI calls (`ack(2,3)`), then one `ack(3,11)`
(~1e9 recursive calls inside C, a single call boundary):

| | JVM | native image |
|---|---|---|
| FFI call overhead | 21 ns/call | 4.0 µs/call |
| jextract-pattern control | 9 ns/call | 3.9 µs/call |
| C compute (`ack(3,11)`) | 45 ms | 44 ms |
| wall incl. startup | 0.70 s | 4.11 s |
| work (sans startup) | 98 ms | 4.11 s |
| peak RSS | 330 MB | 72 MB |

**Memory-limited** — 30 iterations of: C fills a 16 MB native float buffer,
which is deserialized into a boxed Clojure vector (4M `Float`s), retaining a
sliding window of 3 (sustained managed-heap churn, ~300 MB live set):

| | JVM | native image |
|---|---|---|
| wall | 4.0 s | 9.1 s |
| work (sans startup) | 3.2 s | 9.0 s |
| CPU time | 15.2 s | 9.0 s |
| peak RSS | 2.4 GB | 1.0 GB |

**Pure Clojure baselines** — the same workloads with the FFI and C removed
(`cpu-pure`: Ackermann implemented in Clojure; `mem-pure`: the same boxed
vector churn generated from `range`), isolating the runtimes themselves:

| | JVM | native image |
|---|---|---|
| Clojure compute (`ack-clj(3,11)`) | 149 ms | 1.02 s |
| allocation churn wall | 2.4 s | 6.4 s |
| allocation churn work (sans startup) | 1.6 s | 6.4 s |
| peak RSS (compute run) | 338 MB | 17 MB |

## Tuned native image (Oracle GraalVM 25.0.3, G1, PGO)

The community-edition numbers above are the floor. Rebuilding with the G1
collector and profile-guided optimization (both Oracle GraalVM only):

```sh
docker build -f Dockerfile.bench \
  --build-arg BASE_IMAGE=container-registry.oracle.com/graalvm/native-image:25 \
  -t coffi-bench-oracle .
docker run --rm -e PGO=1 -e EXTRA_NATIVE_OPTS=--gc=G1 coffi-bench-oracle
```

Measured in its own session (calibration score 150), with the JVM column
re-measured alongside so the table is fair within itself:

| | JVM | tuned native |
|---|---|---|
| FFI call overhead | 33 ns/call | 1.97 µs/call |
| jextract-pattern control | 9 ns/call | 1.86 µs/call |
| Clojure compute (`ack-clj(3,11)`) | 264 ms | 333 ms |
| cpu-pure wall incl. startup | 0.96 s | **0.34 s** |
| cpu-pure work (sans startup) | **273 ms** | 333 ms |
| mem wall | 3.9 s | **3.1 s** |
| mem work (sans startup) | 3.05 s | 3.00 s |
| mem-pure wall | 2.3 s | **1.8 s** |
| mem-pure work (sans startup) | **1.55 s** | 1.68 s |
| mem peak RSS | 2.4 GB | 2.2 GB |
| peak RSS (cpu-pure run) | 353 MB | 19 MB |

G1 + PGO flips three of five benchmarks to native wins on wall time — both
memory benchmarks and pure-Clojure compute (PGO closes the compute gap
from ~6x to ~1.2x). But the `work` rows show those wall wins are mostly
startup: amortize it and mem is a dead heat while mem-pure and cpu-pure
tip slightly back to the JVM. So tuned native ≈ JVM for long-running
managed-heavy work, and clearly ahead for CLI-style invocations. The
tradeoff is footprint: G1's mem-run peak RSS is 2.2 GB where CE's Serial
GC held 1.0 GB. Per-call FFI improves ~2x over CE (4.0 → 1.97 µs) but
remains ~60x behind the JVM — downcall invocation is unoptimized
([oracle/graal#8113](https://github.com/oracle/graal/issues/8113)), PGO or
not; the fix for that is eager handles, below. One PGO mechanic worth
knowing: profiles from multiple runs must be dumped to separate files and
merged (`--pgo=a.iprof,b.iprof`); a partial profile marks unprofiled hot
paths as cold and makes them slower than no PGO at all.

## Eager downcall handles (GraalVM 25.1+): the native FFI fast path

GraalVM 25.1 supports creating **unbound** downcall handles at image build
time. A baked handle is a compile-time constant, so `invokeExact`
intrinsifies into a direct call to the AOT stub — a plain function-pointer
call — instead of interpreted method-handle dispatch. Coffi's reload
architecture (unbound handles, target address passed per call) is exactly
the shape this path requires, and opts in with
`-J-Dcoffi.ffi.eager-native-image-handles=true` on the native-image
command line. Bound handles — including everything jextract generates —
cannot take it.

Version-numbering gotcha: GraalVM versions its compiler separately from
the JDK. The `ghcr.io/graalvm/native-image-community:25i1` interim images
ship **GraalVM 25.1.3** on JDK 25.0.3 and build this fine, while Oracle's
`container-registry.oracle.com/graalvm/native-image:25` is **GraalVM
25.0.3** (same JDK!) and fails the image build with `linkToNative ...
unexpected input` during analysis — hence the opt-in.

```sh
docker build -f Dockerfile.bench \
  --build-arg BASE_IMAGE=ghcr.io/graalvm/native-image-community:25i1 \
  -t coffi-bench-25i1 .
docker run --rm -e EXTRA_NATIVE_OPTS=-J-Dcoffi.ffi.eager-native-image-handles=true coffi-bench-25i1
```

Measured (session score 161; CE interim, so Serial GC and no PGO — this
isolates exactly the downcall-path change):

| | JVM | native + eager handles |
|---|---|---|
| FFI call overhead | 21 ns/call | **423 ns/call** (4.0 µs without) |
| jextract-pattern control | 9 ns/call | 3.3 µs/call (unchanged) |
| cpu wall incl. startup | 690 ms | **502 ms** |
| cpu work (sans startup) | 95 ms | 493 ms |
| cpu CPU-seconds | 1.72 s | **0.48 s** |

Coffi's per-call cost drops ~9.5x while the jextract control doesn't move:
**coffi is ~8x faster than the jextract pattern on native image**, because
only unbound handles can be baked. With eager handles, native wins the
whole chatty-FFI benchmark on wall time including startup, at ~4x less
CPU; sans startup the JVM stays ~5x ahead on that workload (~20x per
call). The remaining tables are unchanged by the flag — it touches only
the downcall path.

## The shape of the results

Each factor isolated:

- **Pure C compute is at parity** — the same machine code runs either way.
- **Compiled Clojure runs ~7x slower under CE native image** (149 ms vs
  1.02 s, direct-linked on both sides) — HotSpot's C2 with runtime profiles
  beats GraalVM CE's ahead-of-time compilation on hot Clojure code.
  Profile-guided optimization (Oracle GraalVM) closes it to ~1.2x.
- **Allocation-heavy code runs ~3–4x slower with no FFI involved at all** —
  the mem gap is the collector (Serial GC vs G1), not the native boundary.
  And it's a wall-clock gap, not a work gap: native consumes far fewer
  CPU-seconds than the JVM on the mem benchmarks (9.0 s vs 15.2 s) at
  less than half the peak RSS (1.0 GB vs 2.4 GB) — G1 wins wall time by
  collecting on many threads at once. Give native the same G1 (Oracle
  GraalVM) and the memory benchmarks become a dead heat sans startup.
- **Native image wins on startup (~0.6 s vs ~5–40 ms) and idle footprint
  (~4x)** — the classic native-image strengths carry over to FFI programs.
  Which lens you need decides several rows: wall for CLI-style
  invocations, `work` for long-running processes.
- **The JVM wins on chatty FFI: ~190x per call on CE defaults, ~60x with
  G1 + PGO, ~20x with eager handles (GraalVM 25.1+)** — and with eager
  handles native actually wins that benchmark's wall time including
  startup. The per-call cost is GraalVM's, not coffi's: the
  jextract-pattern control measures 3.9 µs/call on native image versus
  coffi's 4.0 µs — downcall invocation is unoptimized in native image
  ([oracle/graal#8113](https://github.com/oracle/graal/issues/8113))
  unless the handle can be baked at build time, which only coffi's unbound
  shape allows (~8x faster than jextract there). Coffi's reload-aware call
  machinery itself cost ~12 ns/call on the JVM when these tables were
  measured (21 ns vs the 9 ns jextract pattern) and ~3% of a CE native
  call; the call-site rework (last section) has since cut that to zero
  by default and ~7 ns with `protected-downcalls`. Bulk segment copies
  (`toArray`, and coffi's primitive-array deserialization built on it) run
  at full speed in both modes.

In short: for CLI-style invocations (startup counts), a GraalVM 25.1+
native image with eager handles wins even the chatty-FFI benchmark
outright, and G1 + PGO wins the memory-bound and compute ones. For
long-running processes (startup amortized — most JVM deployments), the
JVM keeps per-call FFI (~20x at best-tuned native) and roughly ties or
edges out tuned native everywhere else, while native keeps the footprint
advantage. Community-edition defaults undersell native badly: the edition,
collector, profiles, and the eager-handles flag matter far more than `-O`
flags. Numbers are from one machine — rerun the harness on yours.

## Comparing coffi versions (Dockerfile.compare)

A separate harness runs HEAD's benchmark code against two coffi source
trees — this checkout and any older ref, extracted from git history — and
prints them side by side:

```sh
docker build -f Dockerfile.compare -t coffi-compare .   # --build-arg OLD_REF=<sha-or-tag>
docker run --rm coffi-compare
```

JVM only: pre-pure-clojure coffi generates bytecode at runtime (insn),
which cannot go into a native image, so there is no old-native side. The
`cpu-java` and `mem-pure` modes don't touch coffi and run as noise
controls — a delta there is session variance, not a coffi difference.
(Two old-ref compatibility quirks are handled in the harness and bench
source: old `defcfn` binds its symbol eagerly at namespace load, so
`compare.bb` loads the library before requiring `bench.core`; and old
`mem/float-size` carries a broken `^long` tag, so `bench.core` uses
`mem/size-of` instead.)

Measured against `ae3e38a` (v1.0.615+17, the last insn + Java-`Loader`
tree before the pure-clojure transition), two sessions, in-process
`work_ms` / best-round per-call:

| | old (`ae3e38a`) | HEAD | new/old |
|---|---|---|---|
| coffi FFI call overhead | 10 ns/call | 21–22 ns/call | ~2.1x |
| jextract-pattern control | 9 ns/call | 11 ns/call | — (control) |
| C compute (`ack(3,11)`) | 45 ms | 45 ms | 1.0x |
| mem work | 6.0–6.5 s | 4.0–4.1 s | ~0.65x |
| mem-pure control work | 3.0–3.2 s | 2.6–2.9 s | — (control) |

The pure-clojure transition traded per-call speed for deserialization
speed. Old insn-generated call classes sat at ~10 ns/call, essentially the
jextract pattern; HEAD as measured here paid ~11 ns on top. In exchange,
the mem benchmark's coffi-specific portion (mem minus the mem-pure
baseline, ~3.2 s → ~1.4 s) got ~2.3x faster at HEAD, and pure C compute is
at parity, as it must be.

### Where the 11 ns went, and the call-site rework that removed it

Bisecting that 21 ns with single-variable controls (javac vs insn-generated
classes, born-bound vs retargeted call sites, global vs closeable arenas)
decomposed it exactly:

- **9 ns floor** — the downcall itself (the jextract control's cost).
- **~5 ns symbol re-resolution** — the resolver-per-call design: an
  interface call into a closure plus a `ConcurrentHashMap` hit.
- **~7 ns FFM liveness protocol** — the sleeper. Since the pure-clojure
  transition, libraries load via `SymbolLookup/libraryLookup` over a
  *closeable* arena so `unload-library` can wait out in-flight calls;
  every call through such an address pays a scope acquire/release. The old
  `System.load`-based tree never paid this because its addresses had
  non-closeable scopes — and it could never unload, either.

The rework replaces the resolver with a `MutableCallSite` per symbol,
called through an `invokedynamic` instruction in the generated class. The
JIT constant-folds the site's target into a direct native call; library
loads and unloads retarget every site back to a resolving fallback
(`MutableCallSite/syncAll`), deoptimizing compiled callers, and the next
call relinks — so hot reloading works in every mode at zero per-call cost.
Measured against the resolver design (`OLD_REF=develop`, controls flat):

| | resolver (develop) | call sites (default) | + protected-downcalls |
|---|---|---|---|
| coffi FFI call overhead | 21 ns/call | **9 ns/call** | 16 ns/call |
| jextract-pattern control | 9 ns/call | 9 ns/call | 9 ns/call |

By default the resolved address is rebased to the global scope before
binding, eliding the liveness check: **9 ns/call — parity with a
hand-written `static final` downcall handle**. The assumption bought with
that is that libraries are not unloaded or reloaded *while other threads
are inside calls into them* (doing so unmaps code mid-call — undefined
behavior; single-threaded REPL reloading can never trip this). Programs
that do reload under concurrent call load can set
`-Dcoffi.ffi.protected-downcalls=true` to bind addresses library-scoped:
every call then pays the acquire/release (~7 ns single-threaded, more
under contention — the counter's cache line bounces between cores), and
unloads wait for calls in flight.
