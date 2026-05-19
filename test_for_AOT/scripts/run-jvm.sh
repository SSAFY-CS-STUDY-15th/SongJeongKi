#!/usr/bin/env bash
set -euo pipefail

JAR="${1:-build/libs/test-for-aot-0.0.1-SNAPSHOT.jar}"

exec java -jar "${JAR}"
