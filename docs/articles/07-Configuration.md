# Configuration

Coffi needs no configuration beyond the JVM's native-access flag: the
defaults give hot reloading at the REPL and hand-written-downcall
performance in production simultaneously. What remains configurable is one
safety/performance trade on the JVM and one code-generation knob for
native image. This page goes use case by use case.

## Always: the native access flag

The JDK requires explicit permission for any code that calls native
functions:

```
--enable-native-access=ALL-UNNAMED
```

Without it, JDK 24+ prints a warning per restricted operation and future
JDKs refuse. Pass it on the command line (`clj
-J--enable-native-access=ALL-UNNAMED`) or put it in an alias:

```clojure
{:aliases {:dev {:jvm-opts ["--enable-native-access=ALL-UNNAMED"]}}}
```

## Quick reference

| System property | Default | Effect |
|---|---|---|
| `coffi.ffi.protected-downcalls` | `false` | Every downcall acquires its library's scope for the duration of the call (~7 ns/call, more under contention), so `unload-library` and reloads wait for calls in flight on other threads instead of unmapping code mid-call. |
| `coffi.ffi.eager-native-image-handles` | `false` | Native-image builds on GraalVM 25.1+ only: creates unbound downcall handles at image build time so they bake into the image as constants (~9.5x faster native-image FFI calls). No effect on the JVM. |

Background for the sections below, in one paragraph: fns created from
symbol names (`defcfn`, `cfn`, `make-downcall`) call through a per-symbol
`MutableCallSite` reached via an `invokedynamic` instruction. The JIT
constant-folds the site's target into a direct native call, so in steady
state coffi adds nothing over a hand-written `static final` downcall
handle. `load-library` and `unload-library` retarget every site and
deoptimize compiled callers; the next call relinks against the new
library. Reloading therefore costs nothing per call — the only optional
per-call cost is the liveness check described under
`coffi.ffi.protected-downcalls`.

## Interactive development (REPL, hot reload)

**Use the defaults.**

Recompile your shared library and call `load-library` again — fns relink
on their next call. On Windows, `unload-library` first, since the OS locks
the file while loaded. A single-threaded REPL session cannot trip the
concurrent-unload hazard that `protected-downcalls` guards against: you
cannot be inside a native call and reloading at the same time on one
thread.

Reloading's inherent sharp edges (dangling pointers from the old copy,
libraries with threads or TLS destructors, and friends) are documented in
the Getting Started article's "Reloading Libraries" section — none of them
are affected by configuration.

## Production JVM service

**Use the defaults, load libraries once at startup, and don't reload.**

There is no faster mode to switch on: per-call overhead is already at
parity with the jextract pattern (~9 ns on the benchmark machine, equal to
its hand-written control — see the Benchmarks article). A process that
never calls `load-library` on changed files and never calls
`unload-library` after startup has nothing for `protected-downcalls` to
protect.

## Unloading or reloading while other threads are calling

**Set `-Dcoffi.ffi.protected-downcalls=true`.**

The decision rule: could `load-library` (on a changed file) or
`unload-library` run while another thread might be *inside* a call into
that library? If yes — plugin systems that swap libraries under traffic, a
dev server reloading while background threads keep calling — you need
this, because by default a reload unmaps the old library without waiting,
and a thread executing its code at that moment is undefined behavior
(typically a JVM crash).

With the property set, downcall handles bind to addresses scoped to their
library's arena, and the FFM runtime brackets every call with a scope
acquire/release. Unloads and reloads then *wait* for in-flight calls
before unmapping, and calls racing an unload fail with an exception
instead of crashing. The cost is ~7 ns per call single-threaded — roughly
doubling coffi's overhead relative to the 9 ns floor — and more under
heavy multi-threaded call load, because every call does compare-and-swap
on a counter shared across threads, bouncing its cache line between cores.

The property is read when a fn links, so it must be set at JVM startup,
not toggled at runtime.

## GraalVM native image

The JVM call-path machinery above (call sites, `invokedynamic` folding,
`protected-downcalls`) does not apply inside a native image; coffi uses a
separate deferred-linking path there. Configuration for native image is
its own topic — build-time class generation rules, reachability metadata
via `write-native-image-metadata!`, and the
`-J-Dcoffi.ffi.eager-native-image-handles=true` build flag (GraalVM
25.1+), which bakes unbound downcall handles into the image and makes
coffi ~8x faster than the jextract pattern there. See the Native Image
article for the full treatment, and the Benchmarks article for measured
numbers per GraalVM edition and flag.

## Alpine Linux / musl

No configuration needed, but know the platform behavior: musl's `dlclose`
is deliberately a no-op, so old library copies are never unmapped.
Consequences: the concurrent-unload hazard cannot unmap code mid-call
(stale calls hit the old, still-mapped copy), reloading still picks up
recompiled code because the new copy is a fresh file, and each reload
leaks the old mapping until process exit.

## Where to set system properties

```sh
# command line
clojure -J-Dcoffi.ffi.protected-downcalls=true -M:run
java -Dcoffi.ffi.protected-downcalls=true -jar service.jar

# deps.edn alias
{:aliases {:prod-reloading {:jvm-opts ["-Dcoffi.ffi.protected-downcalls=true"]}}}

# containers/CI, without touching the launch command
JAVA_TOOL_OPTIONS=-Dcoffi.ffi.protected-downcalls=true
```
