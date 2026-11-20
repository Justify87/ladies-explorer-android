#!/usr/bin/env bash
# CI02 integration probe on a real build/cache, not a mock Gradle task.
# Run after the normal ARM64 build, with the same Gradle user home and tools.
set -euo pipefail
root="$(git rev-parse --show-toplevel)"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
copy="$tmp/relocated checkout"
mkdir -p "$copy"
git -C "$root" archive HEAD | tar -xf - -C "$copy"

# A Kotlin-only edit must not invalidate the native artifacts.
printf '\n// CI02 synthetic Kotlin-only edit\n' >> "$copy/app/src/main/java/de/justify87/ladiesexplorer/MainActivity.kt"

run_native() {
    local log="$1"
    shift
    (cd "$copy" && ./gradlew :app:uniffiBindgenDebug -Pladies.abis=arm64-v8a \
        -Pladies.nativeCache=true --build-cache --console=plain "$@") 2>&1 | tee "$log"
}

require_task() {
    local log="$1" task="$2" outcome="$3"
    grep -Fx "> Task :app:$task$outcome" "$log"
}

generated="app/build/generated/rust/debug"
test ! -e "$copy/rust/target"
run_native "$tmp/restore.log"
require_task "$tmp/restore.log" cargoBuildDebug " FROM-CACHE"
require_task "$tmp/restore.log" uniffiBindgenDebug " FROM-CACHE"
diff -qr "$root/$generated" "$copy/$generated"
test ! -e "$copy/rust/target"

# Check actual input invalidation with a generator config change. This also
# forces a native build because CI02 conservatively tracks the full workspace.
# The original APK and build outputs are untouched.
sed -i 's/de\.justify87\.ladiesexplorer\.core/de.justify87.ladiesexplorer.cacheprobe/' \
    "$copy/rust/ladies-core/uniffi.toml"
run_native "$tmp/invalidate.log"
require_task "$tmp/invalidate.log" cargoBuildDebug ""
require_task "$tmp/invalidate.log" uniffiBindgenDebug ""
grep -R -m 1 '^package de.justify87.ladiesexplorer.cacheprobe' "$copy/$generated/kotlin"
test -s "$copy/$generated/jniLibs/arm64-v8a/libladies_core.so"

# An embedded non-Rust resource must invalidate the native task on its own.
printf '\n ' >> "$copy/rust/ladies-core/src/source/filter_catalog.json"
run_native "$tmp/resource.log"
require_task "$tmp/resource.log" cargoBuildDebug ""
require_task "$tmp/resource.log" uniffiBindgenDebug ""

# Restoring the pristine configuration must find the original cached results,
# not leave obsolete package directories or stale native files behind.
cp "$root/rust/ladies-core/uniffi.toml" "$copy/rust/ladies-core/uniffi.toml"
cp "$root/rust/ladies-core/src/source/filter_catalog.json" "$copy/rust/ladies-core/src/source/filter_catalog.json"
run_native "$tmp/reset.log"
require_task "$tmp/reset.log" cargoBuildDebug " FROM-CACHE"
require_task "$tmp/reset.log" uniffiBindgenDebug " FROM-CACHE"
diff -qr "$root/$generated" "$copy/$generated"
echo "CI02: relocation, Kotlin-only reuse, binding config and embedded-resource invalidation passed."
