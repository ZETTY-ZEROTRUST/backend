#!/usr/bin/env bash
# 검증·준비·교체는 표준 라이브러리 Python 구현이 담당한다.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
if [ "$#" -gt 1 ]; then
  echo "사용법: scripts/sync-contracts.sh [contracts-source]" >&2
  exit 2
fi
exec python3 "$ROOT/scripts/sync_contracts.py" "$@"
