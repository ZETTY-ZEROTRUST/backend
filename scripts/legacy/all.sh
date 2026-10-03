#!/usr/bin/env bash
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

for s in 01_signup.sh 02_login.sh 03_self.sh 06_negative.sh 07_forged_token.sh; do
    echo "================================================================"
    ./"$s"
    echo
done

echo "================================================================"
echo "all done."
