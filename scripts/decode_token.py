#!/usr/bin/env python3
"""
decode_token.py — JWT 토큰 분해/디코딩 (디버깅용)

사용:
    ./decode_token.py                # ./.token 파일 읽음
    ./decode_token.py <token-string> # 인자로 토큰 직접 전달
    cat .token | ./decode_token.py   # stdin 으로 받음
"""

import base64
import json
import os
import sys


def b64url_decode(s: str) -> bytes:
    s += "=" * (-len(s) % 4)
    return base64.urlsafe_b64decode(s)


def main():
    if len(sys.argv) > 1:
        token = sys.argv[1]
    elif not sys.stdin.isatty():
        token = sys.stdin.read().strip()
    else:
        path = os.path.join(os.path.dirname(__file__), ".token")
        if not os.path.isfile(path):
            sys.exit(f"FAIL: {path} 없음. 02_login.sh 먼저 실행하거나 토큰을 인자로 전달하세요.")
        with open(path) as f:
            token = f.read().strip()

    parts = token.split(".")
    if len(parts) != 3:
        sys.exit(f"FAIL: JWT 형식 아님 (점으로 분리된 3 부분 기대, 실제 {len(parts)})")

    h, p, s = parts

    print("=== HEADER ===")
    header = json.loads(b64url_decode(h))
    print(json.dumps(header, indent=2, ensure_ascii=False))

    print()
    print("=== PAYLOAD ===")
    payload = json.loads(b64url_decode(p))
    print(json.dumps(payload, indent=2, ensure_ascii=False))

    print()
    print("=== SIGNATURE ===")
    sig_bytes = b64url_decode(s)
    print(f"길이: {len(sig_bytes)} 바이트  (ES256: R 32 + S 32 = 64 기대)")
    print(f"hex:  {sig_bytes.hex()}")

    # 부가 정보 — exp/iat 시간 변환
    if "iat" in payload and "exp" in payload:
        from datetime import datetime, timezone
        iat = datetime.fromtimestamp(payload["iat"], tz=timezone.utc).astimezone()
        exp = datetime.fromtimestamp(payload["exp"], tz=timezone.utc).astimezone()
        print()
        print("=== 시간 정보 ===")
        print(f"iat: {iat.isoformat()}")
        print(f"exp: {exp.isoformat()}")
        print(f"수명: {payload['exp'] - payload['iat']}초")


if __name__ == "__main__":
    main()
