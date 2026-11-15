#!/bin/sh
# Synthetic API stubs; NOT a James/ClamAV integration test. Requires JDK 17.
set -eu
here=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT HUP INT TERM
find "$here/test-fixtures" -name '*.java' -print > "$tmp/sources"
find "$here/src/main/java" -name '*.java' -print >> "$tmp/sources"
javac --release 17 -d "$tmp" @"$tmp/sources"
java -cp "$tmp" ru.corvex.james.av.Harness
