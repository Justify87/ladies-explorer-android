#!/usr/bin/env bash
# GitHub-hosted Ubuntu x86_64 only; local development still uses cargo install.
set -euo pipefail
version=4.1.2
name="cargo-ndk-x86_64-unknown-linux-gnu-v$version"
sha=9451622c4567e8abb2c8005001855e32901c0b716ccdd3a173a4375fd03426e1
bin="${1:-${CARGO_HOME:-$HOME/.cargo}/bin}"
cargo_path="$(command -v cargo)"
tools=(cargo-ndk cargo-ndk-env cargo-ndk-runner cargo-ndk-test)

valid() {
    local tool
    for tool in "${tools[@]}"; do
        [ -x "$bin/$tool" ] || return 1
    done
    [ "$(CARGO="$cargo_path" "$bin/cargo-ndk" --version)" = "cargo-ndk $version" ]
}

if valid; then
    echo "CI05: reuse cargo-ndk $version"
    exit 0
fi
test "$(uname -s)" = Linux
test "$(uname -m)" = x86_64
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
curl --fail --silent --show-error --location --retry 3 --connect-timeout 15 --max-time 120 \
    "https://github.com/bbqsrc/cargo-ndk/releases/download/v$version/$name.tgz" -o "$tmp/$name.tgz"
printf '%s  %s\n' "$sha" "$tmp/$name.tgz" | sha256sum --check --strict
mkdir -p "$tmp/unpacked" "$bin"
members=()
for tool in "${tools[@]}"; do
    members+=("$name/$tool")
done
tar -xzf "$tmp/$name.tgz" -C "$tmp/unpacked" --strip-components=1 "${members[@]}"
test "$(CARGO="$cargo_path" "$tmp/unpacked/cargo-ndk" --version)" = "cargo-ndk $version"
for tool in "${tools[@]}"; do
    test -f "$tmp/unpacked/$tool" && test ! -L "$tmp/unpacked/$tool"
    install -m 0755 "$tmp/unpacked/$tool" "$bin/$tool"
done
valid
echo "CI05: installed verified upstream cargo-ndk $version"
