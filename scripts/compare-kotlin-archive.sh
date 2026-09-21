#!/usr/bin/env bash
set -euo pipefail

archive="${1:-}"
if [[ -z "$archive" || ! -f "$archive" ]]; then
  echo "Usage: $0 path/to/archive.zip" >&2
  exit 2
fi

tmp_dir="$(mktemp -d)"
trap 'rm -rf "$tmp_dir"' EXIT
unzip -q "$archive" -d "$tmp_dir"

root="src/main/kotlin/com/focusflow"
printf 'FocusFlow Kotlin archive comparison\narchive: %s\n\n' "$archive"

find "$tmp_dir" -type f -name '*.kt' -print0 | sort -z |
while IFS= read -r -d '' reference; do
  relative="${reference#"$tmp_dir"/}"
  current="$root/$relative"
  if [[ ! -f "$current" ]]; then
    printf 'MISSING  %s\n' "$relative"
  elif cmp -s "$current" "$reference"; then
    printf 'SAME     %s\n' "$relative"
  else
    hunks="$(diff -U0 "$current" "$reference" | grep -c '^@@' || true)"
    printf 'DIFF     %s (%s hunks)\n' "$relative" "$hunks"
  fi
done