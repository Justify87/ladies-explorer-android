#!/usr/bin/env bash
# Native cache probe after ARM64 build; disposable copies, read-only shared cache.
set -euo pipefail
root="$(git rev-parse --show-toplevel)"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
copy="$tmp/relocated checkout"
mkdir -p "$copy"
git -C "$root" archive HEAD | tar -xf - -C "$copy"

cat > "$tmp/read-only-cache.gradle" <<'GRADLE'
gradle.settingsEvaluated { settings ->
    settings.buildCache.local.push = false
    if (settings.buildCache.remote != null) {
        settings.buildCache.remote.push = false
    }
}
GRADLE

# Preserve the dependency cache, but never publish synthetic task outputs.
cache_home="${GRADLE_USER_HOME:-$HOME/.gradle}"
cache_dir="$cache_home/caches/build-cache-1"
cache_entries() {
    if [ -d "$cache_dir" ]; then
        find "$cache_dir" -maxdepth 1 -type f -printf '%f\n' |
            grep -E '^[0-9a-f]{32,64}$' | sort || true
    fi
}
test -d "$cache_dir"
cache_entries > "$tmp/cache-before"
test -s "$tmp/cache-before"

run_native() {
    local log="$1"
    local abi="$2"
    shift 2
    (cd "$copy" && ./gradlew :app:uniffiBindgenDebug "-Pladies.abis=$abi" \
        -Pladies.nativeCache=true --build-cache --console=plain \
        --init-script "$tmp/read-only-cache.gradle" "$@") 2>&1 | tee "$log"
}

require_task() {
    local log="$1" task="$2" outcome="$3"
    grep -Fx "> Task :app:$task$outcome" "$log"
}
require_execution() {
    require_task "$1" cargoBuildDebug ""
    require_task "$1" uniffiBindgenDebug ""
}

generated="app/build/generated/rust/debug"
library="$copy/$generated/jniLibs/arm64-v8a/libladies_core.so"
config="rust/ladies-core/uniffi.toml"
catalog="rust/ladies-core/src/source/filter_catalog.json"
rust_source="rust/ladies-core/src/source/filter.rs"
token="$(basename "$tmp" | tr -cd '[:alnum:]')"
probe_package="de.justify87.ladiesexplorer.cacheprobe_$token"

# Relocation and a changed image label must preserve both native artifacts if tools match.
printf '\n// CI02 synthetic Kotlin-only edit\n' >> "$copy/app/src/main/java/de/justify87/ladiesexplorer/MainActivity.kt"
test ! -e "$copy/rust/target"
ImageVersion="${ImageVersion:?Expected hosted runner image}-ci02-probe" \
    run_native "$tmp/restore.log" arm64-v8a
require_task "$tmp/restore.log" cargoBuildDebug " FROM-CACHE"
require_task "$tmp/restore.log" uniffiBindgenDebug " FROM-CACHE"
diff -qr "$root/$generated" "$copy/$generated"
test ! -e "$copy/rust/target"

# Generator config: verify generated Kotlin package, not just task outcomes.
sed -i "s/de\.justify87\.ladiesexplorer\.core/$probe_package/" "$copy/$config"
run_native "$tmp/config.log" arm64-v8a
require_execution "$tmp/config.log"
grep -R -F -m 1 "package $probe_package" "$copy/$generated/kotlin"
test -s "$library"

# Embedded JSON: verify a unique, real JSON value survives into the ELF.
resource_marker="ci02_resource_$token"
python3 - "$copy/$catalog" "$resource_marker" <<'PY'
import json
import pathlib
import sys
path = pathlib.Path(sys.argv[1])
data = json.loads(path.read_text())
data["categories"][sys.argv[2]] = sys.argv[2]
path.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n")
PY
if grep -aFq "$resource_marker" "$library"; then exit 1; fi
run_native "$tmp/resource.log" arm64-v8a
require_execution "$tmp/resource.log"
grep -aFq "$resource_marker" "$library"

# Change a reachable Rust diagnostic and verify the library bytes.
source_marker="ci02_rust_$token"
sed -i "s/the checked-in source filter catalog is valid JSON/$source_marker/" "$copy/$rust_source"
if grep -aFq "$source_marker" "$library"; then exit 1; fi
run_native "$tmp/source.log" arm64-v8a
require_execution "$tmp/source.log"
grep -aFq "$source_marker" "$library"

# Valid TOML comment isolates the declared Cargo.lock input, not an upgrade.
printf '\n# ci02 lockfile %s\n' "$token" >> "$copy/rust/Cargo.lock"
run_native "$tmp/lock.log" arm64-v8a
require_execution "$tmp/lock.log"
grep -aFq "$source_marker" "$library"
grep -aFq "$resource_marker" "$library"

# Same SDK constant drives Android and cargo-ndk.
sed -i 's/^ladies.minApi=29$/ladies.minApi=30/' "$copy/gradle.properties"
run_native "$tmp/api.log" arm64-v8a
require_task "$tmp/api.log" cargoBuildDebug ""
grep -Fx 'CI02 native build: ABI=arm64-v8a API=30' "$tmp/api.log"
grep -aFq "$source_marker" "$library"

# Actual ABI switch. Reject stale ARM64 outputs and verify ELF architecture.
run_native "$tmp/abi.log" x86_64
require_task "$tmp/abi.log" cargoBuildDebug ""
grep -Fx 'CI02 native build: ABI=x86_64 API=30' "$tmp/abi.log"
test ! -e "$copy/$generated/jniLibs/arm64-v8a"
python3 - "$copy/$generated/jniLibs/x86_64/libladies_core.so" <<'PY'
import pathlib
import struct
import sys
data = pathlib.Path(sys.argv[1]).read_bytes()
assert data[:6] == b"\x7fELF\x02\x01", "Expected little-endian ELF64"
assert struct.unpack_from("<H", data, 18)[0] == 62, "Expected EM_X86_64"
PY
grep -aFq "$source_marker" "$copy/$generated/jniLibs/x86_64/libladies_core.so"
grep -aFq "$resource_marker" "$copy/$generated/jniLibs/x86_64/libladies_core.so"

# Restore only modified inputs. No synthetic package or ABI may survive.
for path in "$config" "$catalog" "$rust_source" rust/Cargo.lock gradle.properties; do
    cp "$root/$path" "$copy/$path"
done
run_native "$tmp/reset.log" arm64-v8a
require_task "$tmp/reset.log" cargoBuildDebug " FROM-CACHE"
require_task "$tmp/reset.log" uniffiBindgenDebug " FROM-CACHE"
diff -qr "$root/$generated" "$copy/$generated"
cache_entries > "$tmp/cache-after"
# Gradle may evict old entries; it must not add synthetic entries.
test -z "$(comm -13 "$tmp/cache-before" "$tmp/cache-after")"
echo "CI02: relocation, read-only cache, Kotlin, generator, JSON, Rust, lockfile, API and ABI checks passed."
