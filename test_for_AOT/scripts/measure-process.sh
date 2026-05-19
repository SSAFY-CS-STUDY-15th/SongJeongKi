#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 1 ]]; then
  echo "usage: scripts/measure-process.sh <pid>" >&2
  exit 1
fi

ps -o pid,etime,rss,vsz,command -p "$1"
