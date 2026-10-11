# SPDX-License-Identifier: Apache-2.0
"""Pure address encodings (bech32, base58) used to compare reference and Scala ledger views."""



# ---------------------------------------------------------------- comparison

BECH32 = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"


def bech32_bytes(text):
    data = [BECH32.index(c) for c in text[text.rindex("1") + 1:-6]]
    acc, bits, out = 0, 0, []
    for v in data:
        acc = (acc << 5) | v
        bits += 5
        while bits >= 8:
            bits -= 8
            out.append((acc >> bits) & 0xff)
    return bytes(out)


def base58_bytes(text):
    alphabet = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
    n = 0
    for c in text:
        n = n * 58 + alphabet.index(c)
    body = n.to_bytes((n.bit_length() + 7) // 8, "big") if n else b""
    return b"\0" * (len(text) - len(text.lstrip("1"))) + body


def address_hex(a):
    return (bech32_bytes(a) if a.startswith(("addr", "stake")) else base58_bytes(a)).hex()
