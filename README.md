# coffi
[![Clojars Project](https://img.shields.io/clojars/v/org.suskalo/coffi.svg)](https://clojars.org/org.suskalo/coffi)
[![cljdoc badge](https://cljdoc.org/badge/org.suskalo/coffi)](https://cljdoc.org/d/org.suskalo/coffi)

Coffi is a foreign function interface library for Clojure, using the [Foreign
Function & Memory API](https://openjdk.org/jeps/454) in JDK 22 and later. This
allows calling native code directly from Clojure without the need for either
Java or native code specific to the library, as e.g. the JNI does. Coffi focuses
on ease of use, including functions and macros for creating wrappers to allow
the resulting native functions to act just like Clojure ones, however this
doesn't remove the ability to write systems which minimize the cost of
marshaling data and optimize for performance, to make use of the low-level
access the FF&M API gives us.

- [Getting Started](https://cljdoc.org/d/org.suskalo/coffi/CURRENT/doc/getting-started)
- [API Documentation](https://cljdoc.org/d/org.suskalo/coffi/CURRENT/api/coffi)
- [Recent Changes](CHANGELOG.md)

## Installation
This library is available on Clojars, or as a git dependency. Add one of the
following entries to the `:deps` key of your `deps.edn`:

```clojure
org.suskalo/coffi {:mvn/version "1.0.615"}
io.github.IGJoshua/coffi {:git/tag "v1.0.615" :git/sha "7401485"}
```

Coffi is pure Clojure with no compilation step, so it can be used as a git
dependency directly — no `clj -X:deps prep` is required.

Coffi also works under GraalVM native-image (GraalVM for JDK 25+); see the
Native Image article and `examples/native-image/` for the recipe.

Coffi requires usage of the package `java.lang.foreign`, and most of the
operations are considered unsafe by the JDK, and are therefore unavailable to
your code without passing some command line flags. In order to use coffi, add
the following JVM arguments to your application.

```sh
--enable-native-access=ALL-UNNAMED
```

You can specify JVM arguments in a particular invocation of the Clojure CLI with
the `-J` flag like so:

``` sh
clj -J--enable-native-access=ALL-UNNAMED
```

You can also specify them in an alias in your `deps.edn` file under the
`:jvm-opts` key (see the next example) and then invoking the CLI with that alias
using `-M`, `-A`, or `-X`.

``` clojure
{:aliases {:dev {:jvm-opts ["--enable-native-access=ALL-UNNAMED"]}}}
```

Other build tools should provide similar functionality if you check their
documentation.

When creating an executable jar file, you can avoid the need to pass this
argument by adding the manifest attribute `Enable-Native-Access: ALL-UNNAMED` to
your jar. See your build tool's documentation for how to add this.

Coffi also includes support for the linter clj-kondo. If you use clj-kondo and
this library's macros are not linting correctly, you may need to install the
config bundled with the library. You can do so with the following shell command,
run from your project directory:

```sh
$ clj-kondo --copy-configs --dependencies --lint "$(clojure -Spath)"
```

## Usage
The two main namespaces are `coffi.mem` which provides functions for allocating
and manipulating off-heap memory and (de)serializing values, and `coffi.ffi`
which can load native libraries, declare native function wrappers, and
(de)serialize functions as callbacks.

```clojure
(require '[coffi.mem :as mem])
(require '[coffi.ffi :as ffi :refer [defcfn]])

(defcfn strlen
  "Given a string, measures its length in bytes."
  strlen [::mem/c-string] ::mem/long)

(strlen "hello")
;; => 5

(ffi/load-system-library "z")
```

In the `coffi.mem` namespace there are types for all the signed primitive
numeric types in C, plus `::mem/pointer` and `::mem/c-string`, and ways to use
malli-like type declarations to define structs, unions, arrays, enums, and
flagsets.

## Alternatives
This library is not the only Clojure library providing access to native code. In
addition the following libraries (among others) exist:

- [dtype-next](https://github.com/cnuernber/dtype-next)
- [tech.jna](https://github.com/techascent/tech.jna)
- [clojure-jna](https://github.com/Chouser/clojure-jna)

Dtype-next has support for Java versions 8-15, 17+, and GraalVM, but is focused
strongly on array-based programming, as well as being focused on keeping memory
in the native side rather than marshaling data to and from Clojure-native
structures. In Java 17+, this uses the Foreign Function & Memory API (a part of
Project Panama until stabilization in JDK 22), while in other Java versions it
uses JNA.

Tech.jna and clojure-jna both use the JNA library in all cases, and neither
provide explicit support for callbacks. JNA allows the use of
`java.nio.ByteBuffer`s to pass structs by value, and both libraries provide ways
to use this by-value construction to call by-reference apis.

An additional alternative to coffi is to directly use the JNI, which is the
longest-standing method of wrapping native code in the JVM, but comes with the
downside that it requires you to write both native and Java code to use, even if
you only intend to use it from Clojure.

If your application needs to be able to run in earlier versions of the JVM than
22, you should consider these other options. Dtype-next provides the most robust
support for native code, but if you are wrapping a simple library then the other
libraries may be more appealing, as they have a smaller API surface area and
it's easier to wrap functions.

There is also a [third party round up](https://docs.google.com/spreadsheets/d/1ViLHNUgrO2osh2AH0h7MaCaXz8g0UpLbyWojY5f10kk/edit?gid=332155605#gid=332155605)
of FFI options for Clojure.

## JVM vs Native Image Benchmarks
Two benchmarks compare running the identical AOT-compiled Clojure program on
the JVM and as a GraalVM native image, both calling C through coffi. Source
and harness live in `examples/benchmarks/` (run with
`docker build -f Dockerfile.bench -t coffi-bench . && docker run --rm coffi-bench`).
Single-shot runs including startup, default settings on both sides, GraalVM
CE 25 in a Linux x64 container; peak RSS and CPU are read from `/proc/self`
by the process itself.

**Calibrating to your machine** — absolute numbers below only hold on the
machine that produced them, so the repo ships a fixed zero-dependency
reference workload that scores any machine on the two axes the benchmarks
stress:

```sh
docker build -f Dockerfile.reference -t coffi-reference . && docker run --rm coffi-reference
```

It prints a cpu time, a mem time, and `score` — their geometric mean, the
machine's single calibration number. The machine behind the tables scores
**161** (`cpu_ms=141 mem_ms=184`). To estimate an absolute number on your
machine, multiply it by your score over 161. Every `Dockerfile.bench` run
prints its own score, and each `RESULT` line reports `ref=` — wall time
divided by that score — so results pasted from different machines are
directly comparable. Ratios *within* one table (JVM vs native, coffi vs
jextract) carry over as-is.

**CPU-limited** — 1M small FFI calls (`ack(2,3)`), then one `ack(3,11)`
(~1e9 recursive calls inside C, a single call boundary):

| | JVM | native image |
|---|---|---|
| FFI call overhead | 41 ns/call | 4.1 µs/call |
| C compute (`ack(3,11)`) | 45 ms | 45 ms |
| wall incl. startup | 0.68 s | 4.10 s |
| startup (wall − work) | ~0.6 s | ~10 ms |
| peak RSS | 351 MB | 73 MB |

**Memory-limited** — 30 iterations of: C fills a 16 MB native float buffer,
which is deserialized into a boxed Clojure vector (4M `Float`s), retaining a
sliding window of 3 (sustained managed-heap churn):

| | JVM | native image |
|---|---|---|
| wall | 4.4 s | 14.4 s |
| CPU time | 21.1 s | 14.2 s |
| peak RSS | 3.4 GB | 4.9 GB |

**Pure Clojure baselines** — the same workloads with the FFI and C removed
(`cpu-pure`: Ackermann implemented in Clojure; `mem-pure`: the same boxed
vector churn generated from `range`), isolating the runtimes themselves:

| | JVM | native image |
|---|---|---|
| Clojure compute (`ack-clj(3,11)`) | 148 ms | 983 ms |
| allocation churn wall | 2.6 s | 15.2 s |
| peak RSS (compute run) | 339 MB | 17 MB |

**Tuned native image** — the community-edition numbers above are the floor,
not the ceiling. Rebuilding with GraalVM 25.1+, the G1 collector
(`EXTRA_NATIVE_OPTS=--gc=G1`, Oracle GraalVM), and coffi's eager downcall
handles (`-J-Dcoffi.ffi.eager-native-image-handles=true`):

| | JVM | CE native | GraalVM 25.1 tuned |
|---|---|---|---|
| FFI call overhead | 41 ns | 4.1 µs | **346 ns** (228 ns with PGO) |
| jextract-pattern control | 21 ns | 3.9 µs | 1.7–2.3 µs |
| Clojure compute | 244 ms | 983 ms | 330 ms |
| mem wall (FFI) | 4.9 s | 14.0 s | **3.8 s** |
| mem-pure wall | 3.1 s | 15.2 s | **3.1 s** (1.8 s with PGO) |
| peak RSS (compute run) | ~350 MB | 73 MB | 18 MB |

The FFI breakthrough: GraalVM 25.1+ supports creating **unbound** downcall
handles at image build time. A baked handle is a compile-time constant, so
`invokeExact` intrinsifies into a direct call to the AOT stub — a plain
function-pointer call — instead of interpreted method-handle dispatch.
Coffi's reload architecture (unbound handles, target address passed per
call) is exactly the required shape, and opts in with
`-J-Dcoffi.ffi.eager-native-image-handles=true` on the native-image command
line (GraalVM 25.0 and older fail the image build with this enabled, hence
opt-in). Bound handles — including everything jextract generates — cannot
take this path, which is why the jextract control stays microseconds while
coffi drops to ~300 ns: **coffi is ~7x faster than the jextract pattern on
native image**, and the tuned CPU benchmark beats the JVM on total wall
time including startup.

Other practical notes from getting there: plain `-O3 -march=native` changed
nothing (the gaps were never about instruction selection); with G1 and PGO,
native image wins both memory benchmarks outright; and PGO profiles from
multiple runs must be dumped to separate files and merged
(`--pgo=a.iprof,b.iprof`) — a stale single profile marks unprofiled hot
paths as cold and makes them slower than no PGO at all.

The shape of the results, with each factor isolated:

- **Pure C compute is at parity** — the same machine code runs either way.
- **Compiled Clojure runs ~6.6x slower under native image** — HotSpot's C2
  with runtime profiles beats GraalVM CE's ahead-of-time compilation on hot
  Clojure code. Profile-guided optimization (Oracle GraalVM) narrows this.
- **Allocation-heavy code runs ~6x slower with no FFI involved at all** —
  the mem gap is the collector and allocation paths (Serial GC vs G1), not
  the native boundary: the FFI variant of the mem benchmark is actually
  slightly *faster* than the pure one on native, since its bulk `toArray`
  reads replace Clojure-side generation.
- **Native image wins on startup (~100x) and idle footprint (~4x)** — the
  classic native-image strengths carry over to FFI programs.
- **The JVM wins decisively on chatty FFI (~100x per call, ~50x with PGO)**,
  and beats *community-edition* native on allocation-heavy managed code
  (~3x) — though Oracle GraalVM with G1 inverts that, as shown above. The
  per-call cost is GraalVM's,
  not coffi's: a control benchmark using the canonical jextract pattern (a
  downcall handle in a static final field, `invokeExact` with exact types
  from plain Java) measures 3.9 µs/call on native image versus coffi's
  4.1 µs — downcall invocation is currently always unoptimized in native
  image ([oracle/graal#8113](https://github.com/oracle/graal/issues/8113)).
  Coffi's reload-aware call machinery itself costs ~22 ns/call on the JVM
  (43 ns vs the 21 ns jextract pattern) and ~4% of a native call. Bulk
  segment copies (`toArray`, and coffi's primitive-array deserialization
  built on it) run at full speed in both modes.

In short: a native image suits coarse-grained FFI (few calls that do real
work in C) and anything where startup or memory footprint dominates — and
with Oracle GraalVM, G1, and PGO it matches or beats the JVM on everything
measured here except per-call FFI latency, which remains the JVM's until
GraalVM optimizes downcalls. Community-edition defaults are markedly slower
on managed-code-heavy workloads; the edition, collector, and profiles
matter far more than `-O` flags. Numbers are from one machine and one
GraalVM version — rerun the harness on yours.

## Known Issues
The project author is aware of these issues and plans to fix them in a future
release:

- When generating docs with codox in a library that depends on coffi, the below error will be produced. A temporary workaround is to add an explicit dependency in your codox build on insn at version 0.2.1
  ```
  Unable to find static field: ACC_OPEN in interface org.objectweb.asm.Opcodes
  ```

## Future Plans
These features are planned for future releases.

- Support for va_args type
- Header parsing tool for generating a data model? (maybe just work with [clong](https://github.com/phronmophobic/clong)?)
- Generic type aliases
- Unsigned integer types
- Helper macro for out arguments
- Improve error messages from defcfn macro
- Mapped memory
- Helper macros for custom serde implementations for more composite data types
- Support for GraalVM Native Image (once their support for FFM becomes mature)

## License

Copyright © 2023 Joshua Suskalo
Copyright © 2026 Magnus Rentsch Ersdal (fork)

Distributed under the Eclipse Public License version 1.0.
