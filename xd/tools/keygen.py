#!/usr/bin/env python3
"""Usage: keygen.py <HWID> <days>   (days 1-30)"""
import hmac, hashlib, sys, time

SECRET = bytes.fromhex("acb9e44aedebff7a3bff7218fddeffcb271e36e9e8237bfa8a2bfca516d55b2d")
ALPHA = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
EPOCH = 1767225600  # 2026-01-01T00:00:00Z


def make_key(short, days, now=None):
    short = short.replace("-", "").upper()
    now = int(time.time()) if now is None else now
    exp = (now - EPOCH) // 3600 + days * 24
    assert 0 <= exp < 65536, "expiry out of range"
    mac = int.from_bytes(hmac.new(SECRET, f"{short}:{exp}".encode(), hashlib.sha256).digest()[:3], "big")
    v = (exp << 24) | mac
    s = "".join(ALPHA[(v >> (5 * (7 - i))) & 31] for i in range(8))
    return f"ASTRAL-{s[:4]}-{s[4:]}"


if __name__ == "__main__":
    if len(sys.argv) != 3 or not 1 <= int(sys.argv[2]) <= 30:
        sys.exit(__doc__)
    print(make_key(sys.argv[1], int(sys.argv[2])))
