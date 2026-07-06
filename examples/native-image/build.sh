#!/usr/bin/env bash
# Builds the hello example as a native binary.
#
# 1. AOT-compile the Clojure code.
# 2. Generate the FFM reachability metadata with coffi: descriptors are
#    recorded when fns are constructed, so requiring the namespaces is
#    enough -- nothing needs to execute.
# 3. Feed that config to native-image.
set -euo pipefail

mkdir -p classes

echo "=== compiling demo shared library ==="
gcc -shared -fPIC native/demo.c -o native/libdemo.so

echo "=== AOT compiling ==="
clojure -J--enable-native-access=ALL-UNNAMED -M -e "(compile 'hello.core)"

# the runtime classpath must contain ONLY AOT classes and jars: with .clj
# sources present, clojure may recompile them at runtime, which native-image
# forbids (runtime class definition)
JARS="$(clojure -Spath | tr ':' '\n' | grep '\.jar$' | paste -sd: -)"
CP="classes:$JARS"

echo "=== coffi-generated foreign metadata ==="
# For apps that need reflection/resource/JNI metadata too (or create FFM
# handles outside coffi), use the native-image tracing agent instead or in
# addition, and pass both directories to -H:ConfigurationFileDirectories:
#   java -agentlib:native-image-agent=config-output-dir=graal-config \
#        --enable-native-access=ALL-UNNAMED -cp "$CP" hello.core
clojure -J--enable-native-access=ALL-UNNAMED -M -e \
  "(require 'hello.core) (println (coffi.ffi/write-native-image-metadata! \"coffi-config\"))"
cat coffi-config/reachability-metadata.json

echo "=== native-image ==="
# clj-easy/graal-build-time registers Clojure namespaces for build-time
# initialization; everything else initializes at runtime
native-image -cp "$CP" hello.core -o hello \
  --no-fallback \
  --features=clj_easy.graal_build_time.InitClojureClasses \
  --initialize-at-build-time=org.objectweb.asm,hello \
  --enable-native-access=ALL-UNNAMED \
  -H:+UnlockExperimentalVMOptions \
  -H:ConfigurationFileDirectories=coffi-config \
  -H:+ReportExceptionStackTraces

echo "=== running native binary ==="
./hello
