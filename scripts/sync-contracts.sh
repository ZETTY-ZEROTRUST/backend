#!/usr/bin/env bash
# C-02 계약(schema·fixture·MANIFEST)을 api-server·auth-server 테스트 리소스로 복사한다.
# 복사본의 sha256은 각 앱의 ContractRevisionTest가 MANIFEST와 대조한다(revision pin).
#
# 사용법:
#   scripts/sync-contracts.sh                 # 기본 원본: <backend 상위>/log-pipeline/contracts
#   scripts/sync-contracts.sh /path/to/contracts
#   CONTRACTS_SRC=/path/to/contracts scripts/sync-contracts.sh
#
# 복사 대상(원본 기준 상대 경로):
#   MANIFEST.json
#   security-event/v2/schema.json, anomaly-detection/v1/schema.json,
#   response-command/v1/schema.json, response-command/v1/result.schema.json
#   security-event/v2/fixtures/** (index·valid·invalid·scenarios)
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SRC="${1:-${CONTRACTS_SRC:-$(dirname "$ROOT")/log-pipeline/contracts}}"

if [ ! -f "$SRC/MANIFEST.json" ]; then
  echo "계약 원본을 찾지 못했다: $SRC (MANIFEST.json 없음)" >&2
  exit 1
fi

FILES=(
  MANIFEST.json
  security-event/v2/schema.json
  anomaly-detection/v1/schema.json
  response-command/v1/schema.json
  response-command/v1/result.schema.json
)

for app in api-server auth-server; do
  DEST="$ROOT/$app/src/test/resources/contracts"
  rm -rf "$DEST"
  for f in "${FILES[@]}"; do
    mkdir -p "$DEST/$(dirname "$f")"
    cp "$SRC/$f" "$DEST/$f"
  done
  mkdir -p "$DEST/security-event/v2"
  cp -R "$SRC/security-event/v2/fixtures" "$DEST/security-event/v2/fixtures"
  echo "$app: $(find "$DEST" -type f | wc -l | tr -d ' ') files"
done

REVISION="$(sed -n 's/.*"revision": *"\([^"]*\)".*/\1/p' "$SRC/MANIFEST.json")"
echo "revision: $REVISION"
echo "revision이 바뀌었으면 각 앱 ContractRevisionTest의 PINNED_REVISION도 함께 갱신한다."
