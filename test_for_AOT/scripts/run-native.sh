#!/usr/bin/env bash
set -euo pipefail

NATIVE_EXECUTABLE="${1:-build/native/nativeCompile/aot-lab}"

exec "./${NATIVE_EXECUTABLE}"
