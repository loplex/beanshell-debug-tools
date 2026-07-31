#!/usr/bin/env bash
# Prints a Keep a Changelog file's section for one version to stdout.
# Exits 1 (no output) if that version has no "## [X.Y.Z]" heading -- the release
# pipeline reads that as "nothing changed here, skip this artifact."
set -euo pipefail

file="$1"
version="$2"

awk -v ver="$version" '
  /^## \[/ {
    if (printed) exit
    if (index($0, "[" ver "]") > 0) { printed = 1 }
    next
  }
  printed { print }
  END { exit !printed }
' "$file"
