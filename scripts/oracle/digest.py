# SPDX-License-Identifier: Apache-2.0
"""State digests: the Python twin of `lab.node.StateDump` (keep the two definitions identical).

An object with more than THRESHOLD fields is replaced by {count, digest, buckets}: the entry count, the sum
modulo 2^256 of SHA-256(key, NUL, canonical value text) over the entries, and the same sum per bucket of the
first nibble of SHA-256(key). The canonical text of a value is compact JSON with object keys sorted by code
point and no whitespace; values are integers, strings, booleans, null, lists and objects (no floats).
"""
import hashlib
import json

THRESHOLD = 64
MOD = 1 << 256


def canon(value):
    return json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=False)


class Digest:
    def __init__(self):
        self.n = 0
        self.total = 0
        self.bucket_n = [0] * 16
        self.bucket_sum = [0] * 16

    def add(self, key, canon_value):
        k = key.encode()
        bucket = hashlib.sha256(k).digest()[0] & 0x0f
        h = int.from_bytes(hashlib.sha256(k + b"\0" + canon_value.encode()).digest(), "big")
        self.n += 1
        self.total = (self.total + h) % MOD
        self.bucket_n[bucket] += 1
        self.bucket_sum[bucket] = (self.bucket_sum[bucket] + h) % MOD

    def state(self):
        """The accumulated sums, to be merged into another digest (parts computed in worker processes)."""
        return self.n, self.total, self.bucket_n, self.bucket_sum

    def merge(self, state):
        n, total, bucket_n, bucket_sum = state
        self.n += n
        self.total = (self.total + total) % MOD
        for i in range(16):
            self.bucket_n[i] += bucket_n[i]
            self.bucket_sum[i] = (self.bucket_sum[i] + bucket_sum[i]) % MOD

    def json(self):
        return {"count": self.n, "digest": f"{self.total:064x}",
                "buckets": [f"{n}:{s:064x}" for n, s in zip(self.bucket_n, self.bucket_sum)]}


def digest_of(items):
    d = Digest()
    for key, value in items:
        d.add(key, canon(value))
    return d.json()


def reduce(value):
    """Every object with more than THRESHOLD fields replaced by its digest (as `StateDump.reduce`)."""
    if isinstance(value, dict):
        if len(value) > THRESHOLD:
            return digest_of(value.items())
        return {k: reduce(v) for k, v in value.items()}
    if isinstance(value, list):
        return [reduce(v) for v in value]
    return value
