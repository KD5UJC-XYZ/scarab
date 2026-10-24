#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Generate the public inputs of the Byron Ed25519 differential corpus (#392).

This writes inputs only: `id`, public key, signature and message, in the column order of
`fixtures/witness/vectors.tsv`. It never writes an expected verdict. The verdicts come from the
unchanged released reference libraries (`scarab-byron-donna`, see README.md), run over this file.

The rows are deterministic (every secret scalar and nonce is a SHA-512 of a fixed label), so a
rerun reproduces the committed file byte for byte. They cover the three classes in which the
ed25519-donna verifier of the Byron path differs from a strict one:

- non-canonical S: S + L and S + kL for k > 1, the top three bits, S = L;
- small-order A and R: the eight torsion points as the key, an identity or torsion R under a real
  or mixed-order key;
- non-canonical A: the 19 encodings with y >= p, and x = 0 with the sign bit set;

with controls (ordinary signatures, flipped bits, encodings off the curve, non-canonical R) and a
seeded random mixture of the same mutations.

Usage: python3 -I scripts/oracle/byron-donna/generate.py > fixtures/witness-byron/generated.tsv
"""

import hashlib
import sys

P = 2**255 - 19
L = 2**252 + 27742317777372353535851937790883648493
D = (-121665 * pow(121666, P - 2, P)) % P
SQRT_M1 = pow(2, (P - 1) // 4, P)


def inv(x):
    return pow(x, P - 2, P)


# Points in extended coordinates (X, Y, Z, T), x = X/Z, y = Y/Z, xy = T/Z.
IDENTITY = (0, 1, 1, 0)


def add(p, q):
    x1, y1, z1, t1 = p
    x2, y2, z2, t2 = q
    a = (y1 - x1) * (y2 - x2) % P
    b = (y1 + x1) * (y2 + x2) % P
    c = 2 * D * t1 * t2 % P
    d = 2 * z1 * z2 % P
    e, f, g, h = b - a, d - c, d + c, b + a
    return (e * f % P, g * h % P, f * g % P, e * h % P)


def neg(p):
    x, y, z, t = p
    return (-x % P, y, z, -t % P)


def mul(k, p):
    result = IDENTITY
    while k > 0:
        if k & 1:
            result = add(result, p)
        p = add(p, p)
        k >>= 1
    return result


def affine(p):
    x, y, z, _ = p
    zi = inv(z)
    return x * zi % P, y * zi % P


def encode(p):
    x, y = affine(p)
    return (y | ((x & 1) << 255)).to_bytes(32, "little")


def recover_x(y, sign):
    """The x of y (taken mod p) with the given sign, or None if y is not on the curve."""
    y %= P
    u = (y * y - 1) % P
    v = (D * y * y + 1) % P
    x2 = u * inv(v) % P
    if x2 == 0:
        return 0
    x = pow(x2, (P + 3) // 8, P)
    if (x * x - x2) % P != 0:
        x = x * SQRT_M1 % P
    if (x * x - x2) % P != 0:
        return None
    if (x & 1) != sign:
        x = P - x
    return x


def decode(data):
    """The point of an encoding, read as ed25519-donna reads A (y mod p, any sign), or None."""
    raw = int.from_bytes(data, "little")
    y = raw & ((1 << 255) - 1)
    x = recover_x(y, raw >> 255)
    if x is None:
        return None
    y %= P
    return (x, y, 1, x * y % P)


def point_of(x, y):
    return (x, y, 1, x * y % P)


BASE = point_of(recover_x(4 * inv(5) % P, 0), 4 * inv(5) % P)


def sha512(*parts):
    h = hashlib.sha512()
    for part in parts:
        h.update(part)
    return h.digest()


def scalar(label):
    return int.from_bytes(sha512(b"scarab-392/" + label.encode()), "little") % L


def le32(n):
    return n.to_bytes(32, "little")


def challenge(r_bytes, a_bytes, message):
    return int.from_bytes(sha512(r_bytes, a_bytes, message), "little") % L


def order(p):
    for k in (1, 2, 4, 8):
        if encode(mul(k, p)) == encode(IDENTITY):
            return k
    return None


def torsion():
    """The eight points of small order, by encoding, with the order of each."""
    found = {}
    four = point_of(recover_x(0, 0), 0)
    # an order-8 point: a square root of the order-4 point, found among the small-order encodings
    for y in range(2, 1 << 16):
        x = recover_x(y, 0)
        if x is None:
            continue
        q = mul(L, point_of(x, y))
        if order(q) == 8:
            eight = q
            break
    for k in range(8):
        q = mul(k, eight)
        found[encode(q)] = order(q)
    assert encode(mul(2, eight)) in (encode(four), encode(neg(four)))
    return found


def sign(a, a_bytes, message, nonce):
    r_point = mul(nonce, BASE)
    r_bytes = encode(r_point)
    s = (nonce + challenge(r_bytes, a_bytes, message) * a) % L
    return r_bytes, s


def search(a_bytes, a_point, message, base_scalar, offset_order, offset):
    """R = [s]B - [j]T and S = s with H(R, A, M) = j mod ord(T): a signature for a key whose
    torsion part T is all that [h]A depends on beyond [s]B. Tries successive s from the label."""
    for attempt in range(256):
        s = (base_scalar + attempt) % L
        sb = mul(s, BASE)
        for j in range(offset_order):
            r_bytes = encode(add(sb, neg(mul(j, offset))))
            h = challenge(r_bytes, a_bytes, message)
            if encode(mul(h, a_point)) == encode(mul(j, offset)) and h % offset_order == j:
                return r_bytes, s
    raise RuntimeError("no signature found")


ROWS = []


def row(ident, key, signature, message):
    assert len(key) == 32 and len(signature) == 64
    ROWS.append((ident, key.hex(), signature.hex(), message.hex()))


def byron_message(tag, label):
    # the shape of a Byron signed message: a sign tag, a CBOR protocol magic, then the payload
    return bytes([tag]) + bytes.fromhex("1a2d964a09") + sha512(label.encode())[:32]


MESSAGES = [
    b"",
    b"\x72",
    byron_message(0x01, "tx"),
    byron_message(0x02, "redeem"),
    byron_message(0x0A, "certificate"),
]


def ordinary():
    for i, message in enumerate(MESSAGES):
        a = scalar(f"key/{i}")
        a_point = mul(a, BASE)
        a_bytes = encode(a_point)
        r_bytes, s = sign(a, a_bytes, message, scalar(f"nonce/{i}"))
        good = r_bytes + le32(s)
        row(f"control-valid-{i}", a_bytes, good, message)
        row(f"control-other-message-{i}", a_bytes, good, message + b"\x00")
        for bit in (0, 255, 256, 511):
            flipped = bytearray(good)
            flipped[bit // 8] ^= 1 << (bit % 8)
            row(f"control-flip-{bit}-{i}", a_bytes, bytes(flipped), message)
        # non-canonical S: every S + kL that fits in 256 bits; only k = 1 stays below 2^253
        k = 1
        while s + k * L < 2**256:
            row(f"s-plus-{k}L-{i}", a_bytes, r_bytes + le32(s + k * L), message)
            k += 1
        for top in (0x20, 0x40, 0x80, 0xE0):
            high = bytearray(good)
            high[63] |= top
            row(f"s-top-{top:02x}-{i}", a_bytes, bytes(high), message)
        # small-order R under a prime-order key: R = identity, S = h a, so [S]B - [h]A = 0
        r_identity = encode(IDENTITY)
        h = challenge(r_identity, a_bytes, message)
        row(f"r-identity-{i}", a_bytes, r_identity + le32(h * a % L), message)
        row(f"r-identity-s-plus-L-{i}", a_bytes, r_identity + le32(h * a % L + L), message)


def small_order_keys(points):
    for n, (enc, ordr) in enumerate(sorted(points.items())):
        a_point = decode(enc)
        for i, message in enumerate(MESSAGES[:3]):
            label = scalar(f"small/{n}/{i}")
            r_bytes, s = search(enc, a_point, message, label, ordr, a_point)
            row(f"a-order{ordr}-{n}-{i}", enc, r_bytes + le32(s), message)
            row(f"a-order{ordr}-{n}-s-plus-L-{i}", enc, r_bytes + le32(s + L), message)
        # A and R both of small order, S = 0 (or L): [0]B - [h]A must be R
        for i, message in enumerate(MESSAGES):
            for r_enc in sorted(points):
                h = challenge(r_enc, enc, message)
                if encode(neg(mul(h, a_point))) == r_enc:
                    row(f"ar-small-{n}-{i}", enc, r_enc + le32(0), message)
                    row(f"ar-small-{n}-s-is-L-{i}", enc, r_enc + le32(L), message)
                    break


def mixed_order_keys(points):
    eight = [decode(e) for e, o in sorted(points.items()) if o == 8][0]
    for i, message in enumerate(MESSAGES[:3]):
        a = scalar(f"mixed/{i}")
        a_point = add(mul(a, BASE), eight)
        a_bytes = encode(a_point)
        # R = [r]B - [j]T, S = r + h a, accepted when H(R, A, M) = j mod 8
        nonce = scalar(f"mixed-nonce/{i}")
        rb = mul(nonce, BASE)
        for attempt in range(512):
            m = message + attempt.to_bytes(2, "big") if attempt else message
            done = False
            for j in range(8):
                r_bytes = encode(add(rb, neg(mul(j, eight))))
                h = challenge(r_bytes, a_bytes, m)
                if h % 8 == j:
                    row(f"a-mixed-{i}", a_bytes, r_bytes + le32((nonce + h * a) % L), m)
                    done = True
                    break
            if done:
                break
        # R of small order under the mixed-order key: S = h a, accepted when -[h]T = R
        for attempt in range(512):
            m = message + b"r" + attempt.to_bytes(2, "big")
            found = False
            for r_enc in sorted(points):
                h = challenge(r_enc, a_bytes, m)
                if encode(neg(mul(h, eight))) == r_enc:
                    row(f"r-small-mixed-key-{i}", a_bytes, r_enc + le32(h * a % L), m)
                    found = True
                    break
            if found:
                break


def non_canonical_keys(points):
    # the 19 encodings with y >= p (y = p + t, t = 0..18), each with sign bit clear and set
    for t in range(19):
        for sign_bit in (0, 1):
            raw = (P + t) | (sign_bit << 255)
            enc = raw.to_bytes(32, "little")
            a_point = decode(enc)
            ident = f"a-y-p-plus-{t}-sign{sign_bit}"
            if a_point is None:
                row(f"{ident}-off-curve", enc, bytes(64), MESSAGES[0])
                continue
            ordr = order(a_point)
            if ordr is not None:
                for i, message in enumerate(MESSAGES[:2]):
                    r_bytes, s = search(
                        enc, a_point, message, scalar(f"{ident}/{i}"), ordr, a_point
                    )
                    row(f"{ident}-order{ordr}-{i}", enc, r_bytes + le32(s), message)
                    # the same signature under the canonical encoding of the same point: the
                    # challenge hashes the key's original bytes, so the verdict may differ
                    row(f"{ident}-order{ordr}-as-canonical-{i}", encode(a_point),
                        r_bytes + le32(s), message)
            else:
                # no discrete logarithm is known: an arbitrary signature, decoded and refused
                r_bytes, s = sign(scalar(ident), encode(mul(scalar(ident), BASE)),
                                  MESSAGES[0], scalar(ident + "/n"))
                row(f"{ident}-unknown-log", enc, r_bytes + le32(s), MESSAGES[0])
    # x = 0 with the sign bit set: the identity and the order-2 point
    for name, y in (("identity", 1), ("order2", P - 1)):
        enc = (y | (1 << 255)).to_bytes(32, "little")
        a_point = decode(enc)
        ordr = order(a_point)
        for i, message in enumerate(MESSAGES[:2]):
            r_bytes, s = search(enc, a_point, message, scalar(f"x0/{name}/{i}"), ordr, a_point)
            row(f"a-x0-sign-{name}-{i}", enc, r_bytes + le32(s), message)


def non_canonical_r():
    # under the identity key [S]B - [h]A = [S]B; S = 0 gives the identity, which only its
    # canonical encoding 01 00 .. 00 matches
    a_bytes = encode(IDENTITY)
    for name, raw in (
        ("canonical", 1),
        ("y-p-plus-1", P + 1),
        ("x0-sign", 1 | (1 << 255)),
        ("y-p-plus-1-sign", (P + 1) | (1 << 255)),
    ):
        row(f"r-identity-{name}", a_bytes, raw.to_bytes(32, "little") + le32(0), MESSAGES[2])


def off_curve():
    for y in range(2, 40):
        if recover_x(y, 0) is None:
            enc = y.to_bytes(32, "little")
            a = scalar(f"off/{y}")
            r_bytes, s = sign(a, encode(mul(a, BASE)), MESSAGES[2], scalar(f"off-n/{y}"))
            row(f"control-a-off-curve-{y}", enc, r_bytes + le32(s), MESSAGES[2])
            row(f"control-r-off-curve-{y}", encode(mul(a, BASE)), enc + le32(s), MESSAGES[2])
            if len([r for r in ROWS if r[0].startswith("control-a-off-curve")]) >= 4:
                break


def mixture(points):
    """Seeded random combinations of the mutations above, over random keys and messages."""
    small = sorted(points)
    for n in range(256):
        seed = sha512(b"scarab-392/mixture/" + n.to_bytes(4, "big"))
        choice = seed[0]
        message = seed[1 : 1 + seed[1] % 48]
        a = int.from_bytes(seed[:32], "little") % L
        torsion_part = decode(small[seed[2] % 8])
        a_point = add(mul(a, BASE), torsion_part) if choice & 1 else mul(a, BASE)
        a_bytes = encode(a_point)
        nonce = int.from_bytes(seed[32:], "little") % L
        if choice & 2:
            r_bytes, s = small[seed[3] % 8], 0
            h = challenge(r_bytes, a_bytes, message)
            s = h * a % L
        else:
            r_bytes, s = sign(a, a_bytes, message, nonce)
        if choice & 4:
            s += L * (1 + (seed[4] % 2) * (seed[5] % 15))
        if choice & 8 and int.from_bytes(a_bytes, "little") & ((1 << 255) - 1) < 19:
            y = int.from_bytes(a_bytes, "little")
            a_bytes = (y + P).to_bytes(32, "little")
        signature = r_bytes + (s % 2**256).to_bytes(32, "little")
        if choice & 16:
            flipped = bytearray(signature)
            flipped[seed[6] % 64] ^= 1 << (seed[7] % 8)
            signature = bytes(flipped)
        row(f"mixture-{n}", a_bytes, signature, message)


def main():
    points = torsion()
    assert len(points) == 8 and sorted(points.values()) == [1, 2, 4, 4, 8, 8, 8, 8]
    ordinary()
    small_order_keys(points)
    mixed_order_keys(points)
    non_canonical_keys(points)
    non_canonical_r()
    off_curve()
    mixture(points)
    ids = [r[0] for r in ROWS]
    assert len(ids) == len(set(ids)), "duplicate id"
    sys.stdout.write("".join("\t".join(r) + "\n" for r in ROWS))


if __name__ == "__main__":
    main()
