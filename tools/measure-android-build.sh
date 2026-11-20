#!/usr/bin/env bash
# CI05 one-off measurement (manual or CI05 build-contract PR).
# Use a disposable checkout and the already populated read-only task cache.
# The checked APK, production build outputs and original checkout stay intact.
set -euo pipefail
root="$(git rev-parse --show-toplevel)"
reports="$root/build/reports/ci05"
mkdir -p "$reports"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
copy="$tmp/checkout"
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

# Exercise the missing-binary and warm-install branches without touching Cargo home.
/usr/bin/time -f "%e" -o "$reports/ndk-cold-seconds.txt" bash "$root/tools/install-cargo-ndk.sh" "$tmp/ndk-bin" | tee "$reports/ndk-cold.log"
grep -Fx 'CI05: installed verified upstream cargo-ndk 4.1.2' "$reports/ndk-cold.log"
sha256sum "$tmp/ndk-bin/"* > "$tmp/ndk-before"
/usr/bin/time -f "%e" -o "$reports/ndk-warm-seconds.txt" bash "$root/tools/install-cargo-ndk.sh" "$tmp/ndk-bin" | tee "$reports/ndk-warm.log"
grep -Fx 'CI05: reuse cargo-ndk 4.1.2' "$reports/ndk-warm.log"
sha256sum -c "$tmp/ndk-before"

tasks=(:app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest :app:lintDebug)
printf 'scenario\tseconds\tgradle_exit\n' > "$reports/timings.tsv"

run_build() {
    local scenario="$1"
    shift
    local start end status=0
    start="$(date +%s%N)"
    (cd "$copy" && ./gradlew "${tasks[@]}" -Pladies.abis=arm64-v8a \
        -Pladies.nativeCache=true --continue --build-cache --console=plain \
        --init-script "$tmp/read-only-cache.gradle" \
        -Pkotlin.build.report.output=file --info -Dorg.gradle.caching.debug=true \
        "$@") > "$reports/$scenario.log" 2>&1 || status=$?
    end="$(date +%s%N)"
    # Keep the native Gradle diagnostic report even if qualification fails.
    for dir in "$copy/build/reports/configuration-cache" "$copy/app/build/reports/configuration-cache"; do
        if [ -d "$dir" ]; then
            mkdir -p "$reports/$scenario-configuration-cache"
            cp -a "$dir/." "$reports/$scenario-configuration-cache/"
        fi
    done
    python3 - "$scenario" "$start" "$end" "$status" <<'PY' >> "$reports/timings.tsv"
import sys
scenario, start, end, status = sys.argv[1:]
print(f"{scenario}\t{(int(end)-int(start))/1e9:.3f}\t{status}")
PY
    grep -E '^> Task :app:(cargoBuildDebug|uniffiBindgenDebug|compileDebugKotlin|lintAnalyzeDebug|lintAnalyzeDebugUnitTest|lintAnalyzeDebugAndroidTest)|^BUILD (SUCCESSFUL|FAILED)|[Cc]onfiguration cache (entry|problem)|^Reusing configuration cache' \
        "$reports/$scenario.log" || true
    return "$status"
}

# Restore task outputs from the same existing cache for fair comparisons.
# Only delete generated build outputs, not configuration or dependency caches.
reset_outputs() {
    rm -rf "$copy/app/build" "$copy/build"
}
check_native_outputs() {
    diff -qr "$root/app/build/generated/rust/debug" "$copy/app/build/generated/rust/debug"
}
save_kotlin_reports() {
    local scenario="$1"
    mkdir -p "$reports/$scenario-kotlin"
    for dir in "$copy/build/reports/kotlin-build" "$copy/app/build/reports/kotlin-build"; do
        if [ -d "$dir" ]; then cp -a "$dir/." "$reports/$scenario-kotlin/"; fi
    done
}
run_baseline() {
    reset_outputs
    run_build "$1" --no-configuration-cache
    check_native_outputs
    grep -Fx "> Task :app:cargoBuildDebug FROM-CACHE" "$reports/$1.log"
    grep -Fx "> Task :app:uniffiBindgenDebug FROM-CACHE" "$reports/$1.log"
    save_kotlin_reports "$1"
}

run_baseline baseline-first
# Qualification is intentionally diagnostic: an unsupported configuration cache
# is a negative result, not a reason to weaken the required normal build.
reset_outputs
if run_build cc-first --configuration-cache --configuration-cache-problems=fail; then
    check_native_outputs
    save_kotlin_reports cc-first
    if grep -Fq 'Configuration cache entry stored' "$reports/cc-first.log"; then
        for i in 1 2 3; do
            run_baseline "baseline-$i"
            reset_outputs
            if ! run_build "cc-$i" --configuration-cache --configuration-cache-problems=fail; then
                echo 'Configuration cache qualification failed; leave disabled.' | tee "$reports/decision.txt"
                break
            fi
            check_native_outputs
            save_kotlin_reports "cc-$i"
            if ! grep -Fq 'Reusing configuration cache.' "$reports/cc-$i.log"; then
                echo 'Configuration cache not reused; leave disabled.' | tee "$reports/decision.txt"
                break
            fi
        done
        if [ ! -f "$reports/decision.txt" ]; then
            echo 'Configuration cache compatible in repeated clean-output builds; compare median times before adoption.' > "$reports/decision.txt"
        fi
    else
        echo 'Configuration cache storage not proved; leave disabled.' > "$reports/decision.txt"
    fi
else
    echo 'Configuration cache incompatible in first build; leave disabled and inspect report/log.' > "$reports/decision.txt"
fi

# Use baseline mode to diagnose a typical UI edit and isolate Lint keys.
# A comment changes source content without introducing a functional change.
printf '\n// CI05 synthetic Kotlin edit\n' >> "$copy/app/src/main/java/de/justify87/ladiesexplorer/MainActivity.kt"
reset_outputs
run_build kotlin-edit --no-configuration-cache
check_native_outputs
save_kotlin_reports kotlin-edit

# Rust mutation uses the already verified explicit source boundary. The
# CI02 probe exercises actual ABI/API and ELF-content changes separately.
printf '\n// CI05 synthetic Rust edit\n' >> "$copy/rust/ladies-core/src/source/filter.rs"
reset_outputs
run_build rust-edit --no-configuration-cache
save_kotlin_reports rust-edit
test -s "$copy/app/build/generated/rust/debug/jniLibs/arm64-v8a/libladies_core.so"

cat "$reports/timings.tsv"
cat "$reports/decision.txt"
# Never publish synthetic output archives or caches.
