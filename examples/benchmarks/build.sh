#!/usr/bin/env bash
# Builds bench.core both as AOT classes (JVM mode) and a native image, then
# runs the cpu and mem benchmarks in both modes. Wall time is measured here;
# each run prints its own peak RSS and CPU seconds from /proc/self.
set -euo pipefail

mkdir -p classes graal-config

echo "=== compiling bench shared library ==="
gcc -O2 -shared -fPIC native/bench.c -o native/libbench.so

echo "=== compiling java stub ==="
javac -d classes java/BenchStub.java

echo "=== AOT compiling ==="
clojure -J--enable-native-access=ALL-UNNAMED -M -e "(compile 'bench.core)"

JARS="$(clojure -Spath | tr ':' '\n' | grep '\.jar$' | paste -sd: -)"
CP="classes:$JARS"

echo "=== coffi-generated foreign metadata ==="
# BenchStub's descriptor (jlong(jlong,jlong)) is covered too: registration
# is per descriptor, and coffi's ack fn records the identical one
clojure -J--enable-native-access=ALL-UNNAMED -M -e \
  "(require 'bench.core) (println (coffi.ffi/write-native-image-metadata! \"graal-config\"))"

FLAGS=(
  --no-fallback
  --features=clj_easy.graal_build_time.InitClojureClasses
  --initialize-at-build-time=org.objectweb.asm,bench
  --enable-native-access=ALL-UNNAMED
  -H:+UnlockExperimentalVMOptions
  -H:ConfigurationFileDirectories=graal-config
  -H:+ReportExceptionStackTraces
  # the ackermann benchmarks recurse ~16k deep; frame sizes vary by
  # edition/GC, so pin a roomy runtime default stack
  -R:StackSize=16m
)

# PGO=1 builds an instrumented binary, runs the benchmarks to collect a
# profile, and feeds it to the optimizing build (Oracle GraalVM only)
PGO_FLAG=""
if [ -n "${PGO:-}" ]; then
  echo "=== native-image (PGO instrumented) ==="
  native-image -cp "$CP" bench.core -o bench-instr "${FLAGS[@]}" --pgo-instrument
  echo "=== PGO profiling runs ==="
  # one profile file per run: each run overwrites its dump file, so give
  # them distinct names and merge via --pgo's comma list
  ./bench-instr -XX:ProfilesDumpFile=cpu-pure.iprof cpu-pure > /dev/null
  ./bench-instr -XX:ProfilesDumpFile=mem-pure.iprof mem-pure > /dev/null
  ./bench-instr -XX:ProfilesDumpFile=cpu.iprof cpu > /dev/null
  ./bench-instr -XX:ProfilesDumpFile=mem.iprof mem > /dev/null
  PGO_FLAG="--pgo=cpu-pure.iprof,mem-pure.iprof,cpu.iprof,mem.iprof"
fi

echo "=== native-image ==="
# EXTRA_NATIVE_OPTS: e.g. "-O3 -march=native" or "--gc=G1" (Oracle GraalVM)
native-image -cp "$CP" bench.core -o bench "${FLAGS[@]}" \
  ${PGO_FLAG:-} ${EXTRA_NATIVE_OPTS:-}

run_timed () {
  local label="$1"; shift
  local start end
  start=$(date +%s%N)
  "$@"
  end=$(date +%s%N)
  echo "$label wall_ms=$(( (end - start) / 1000000 ))"
}

for mode in cpu cpu-java cpu-pure mem mem-pure; do
  echo "--- $mode / jvm ---"
  run_timed "RESULT $mode jvm" \
    java --enable-native-access=ALL-UNNAMED -Xss16m -cp "$CP" bench.core "$mode"
  echo "--- $mode / native ---"
  run_timed "RESULT $mode native" ./bench "$mode"
done
