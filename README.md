# coffi

TODO:rewrite

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

An additional alternative to coffi is to directly use the JNI, which is the
longest-standing method of wrapping native code in the JVM, but it requires you to write both native and Java code.

If your application needs to be able to run in earlier versions of the JVM than
22, you should consider these other options.

## JVM vs Native Image Benchmarks
Benchmarks comparing the identical AOT-compiled Clojure program on the JVM
and as a GraalVM native image, both calling C through coffi, live in
[BENCHMARKS.md](BENCHMARKS.md) — methodology, machine calibration, full
tables, and tuning knobs. Harness and source: `examples/benchmarks/`.
Headline: bulk copies and C compute run at full speed in both modes;
native image wins startup and footprint; a warm JVM wins chatty per-call
FFI (a GraalVM downcall limitation, not coffi's); and on GraalVM 25.1+
with coffi's eager downcall handles, native per-call cost drops ~9.5x to
~420 ns — ~8x faster than the jextract pattern, whose bound handles
cannot take that path.


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

## License

Copyright © 2023 Joshua Suskalo
Copyright © 2026 Magnus Rentsch Ersdal (fork)

Distributed under the Eclipse Public License version 1.0.
