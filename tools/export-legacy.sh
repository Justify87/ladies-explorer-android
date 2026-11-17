#!/usr/bin/env bash
set -euo pipefail

usage() {
    echo "Usage: $0 /path/to/ladies-webapp/data /path/to/ladies-export.zip" >&2
    exit 2
}

[[ $# -eq 2 ]] || usage
command -v sqlite3 >/dev/null || { echo "sqlite3 is required" >&2; exit 1; }
command -v zip >/dev/null || { echo "zip is required" >&2; exit 1; }

source_dir="$(cd "$1" && pwd -P)"
source_db="$source_dir/ladies.sqlite3"
[[ -f "$source_db" ]] || { echo "Missing $source_db" >&2; exit 1; }
output_dir="$(cd "$(dirname "$2")" && pwd -P)"
output="$output_dir/$(basename "$2")"
[[ "$output" == *.zip ]] || usage
[[ ! -e "$output" ]] || { echo "Output already exists: $output" >&2; exit 1; }

umask 077
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

# SQLite .backup includes a consistent snapshot of committed WAL changes.
snapshot="$work/legacy.sqlite3"
escaped="$(printf '%s' "$snapshot" | sed "s/'/''/g")"
sqlite3 -readonly "$source_db" ".backup '$escaped'"
integrity="$(sqlite3 -readonly "$snapshot" "PRAGMA integrity_check;")"
[[ "$integrity" == "ok" ]] || { echo "SQLite integrity check failed: $integrity" >&2; exit 1; }
version="$(sqlite3 -readonly "$snapshot" "PRAGMA user_version;")"
[[ "$version" == "1" ]] || { echo "Expected web-app schema v1, got $version" >&2; exit 1; }

printf 'ladies-explorer-legacy-v1\n' > "$work/manifest.txt"
mkdir -p "$work/assets"
for subdir in media raw_profiles; do
    if [[ -d "$source_dir/ladies_data/$subdir" ]]; then
        cp -R "$source_dir/ladies_data/$subdir" "$work/assets/$subdir"
    fi
done

# Do not silently omit referenced photos, videos or archived HTML.
check_references() {
    local table="$1" column="$2" relative
    while IFS= read -r relative; do
        [[ -n "$relative" ]] || continue
        if [[ "$relative" = /* || "$relative" == ../* || "$relative" == *"/../"* ||
              "$relative" == *"/./"* || "$relative" == *'\'* ]]; then
            echo "Unsafe archive path: $relative" >&2
            return 1
        fi
        case "$relative" in
            media/*|raw_profiles/*) ;;
            *) echo "Unexpected archived file path: $relative" >&2; return 1 ;;
        esac
        [[ -f "$work/assets/$relative" ]] || {
            echo "Referenced archived file missing: $relative" >&2
            return 1
        }
    done < <(sqlite3 -readonly "$snapshot" "SELECT $column FROM $table WHERE $column IS NOT NULL;")
}
check_references media_assets local_path
check_references listing_snapshots raw_html_path

(
    cd "$work"
    zip -q -r -X "$output" manifest.txt legacy.sqlite3 assets
)
echo "Created migration bundle: $output"
echo "This ZIP contains personal records and archived media. Keep it private."
