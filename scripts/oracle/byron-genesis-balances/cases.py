#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Cases for the Byron genesis balances oracle (#728).

Each case is the text of a genesis object with its two balance fields, `nonAvvmBalances` (Base58
Byron address -> lovelace) and `avvmDistr` (base64url redeem key -> lovelace), as
`readGenesisData` reads them. The addresses cover each way `decodeAddressBase58` can refuse one
(Base58, the CBOR envelope, the CRC-32, the payload) and the encodings it accepts that are not the
canonical ones; the amounts cover the forms `readEither` takes as a `Word64` and refuses.

`--inputs` writes the harness input; `--reference` with `--out` merges the harness output into
`cases.jsonl`, a line per case with its text and the reference's verdict.
"""
import argparse
import base64
import json
import re
import zlib
from pathlib import Path

ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"


def b58(raw):
    n = int.from_bytes(raw, "big")
    out = ""
    while n:
        n, r = divmod(n, 58)
        out = ALPHABET[r] + out
    return "1" * (len(raw) - len(raw.lstrip(b"\0"))) + out


def head(major, n, width=None):
    """A CBOR head; `width` forces the argument into 1, 2, 4 or 8 bytes."""
    if width is None:
        width = 0 if n < 24 else 1 if n < 0x100 else 2 if n < 0x10000 else 4 if n < 0x100000000 else 8
    if width == 0:
        return bytes([major << 5 | n])
    code = {1: 24, 2: 25, 4: 26, 8: 27}[width]
    return bytes([major << 5 | code]) + n.to_bytes(width, "big")


def uint(n, width=None):
    return head(0, n, width)


def bstr(b, width=None):
    return head(2, len(b), width) + b


def arr(items, width=None, indef=False):
    if indef:
        return b"\x9f" + b"".join(items) + b"\xff"
    return head(4, len(items), width) + b"".join(items)


def cmap(pairs, width=None):
    return head(5, len(pairs), width) + b"".join(k + v for k, v in pairs)


def tag(t, inner, width=None):
    return head(6, t, width) + inner


ROOT = bytes(range(1, 29))
MAGIC = 1097911063


def payload(root=ROOT, attributes=None, kind=0):
    return arr([bstr(root), attributes if attributes is not None else cmap([]), uint(kind)])


def address(body=None, crc=None, crc_width=None, tag_width=None, bytes_width=None, outer=None):
    """`[#6.24(bytes .cbor [root, attributes, type]), crc32]`, each part replaceable."""
    body = payload() if body is None else body
    check = zlib.crc32(body) if crc is None else crc
    parts = [tag(24, bstr(body, bytes_width), tag_width), uint(check, crc_width)]
    return arr(parts) if outer is None else outer(parts)


def magic_attr(value=MAGIC, inner_width=None):
    return cmap([(uint(2), bstr(uint(value, inner_width)))])


def addresses():
    """Name -> address bytes."""
    canonical = address()
    yield "canonical, no attributes", canonical
    yield "network magic attribute", address(payload(attributes=magic_attr()))
    yield "redeem type", address(payload(kind=2))
    yield "derivation path attribute", address(payload(attributes=cmap([(uint(1), bstr(bstr(b"\x01" * 12)))])))
    yield "unknown attribute", address(payload(attributes=cmap([(uint(5), bstr(b"\xaa\xbb"))])))
    yield "checksum in eight bytes", address(crc_width=8)
    yield "tag 24 in two bytes", address(tag_width=2)
    yield "payload length in two bytes", address(bytes_width=2)
    yield "root length in two bytes", address(arr([bstr(ROOT, 2), cmap([]), uint(0)]))
    yield "type in two bytes", address(arr([bstr(ROOT), cmap([]), uint(0, 2)]))
    yield "attribute map head in two bytes", address(arr([bstr(ROOT), cmap([], 2), uint(0)]))
    yield "outer list head in two bytes", address(outer=lambda ps: arr(ps, 2))
    # refusals
    yield "wrong checksum", address(crc=zlib.crc32(payload()) ^ 1)
    yield "checksum over 32 bits", address(crc=zlib.crc32(payload()) + (1 << 32))
    yield "tag 25 envelope", arr([tag(25, bstr(payload())), uint(zlib.crc32(payload()))])
    yield "envelope of three items", arr([tag(24, bstr(payload())), uint(zlib.crc32(payload())), uint(0)])
    yield "indefinite envelope", address(outer=lambda ps: arr(ps, indef=True))
    yield "byte after the address", canonical + b"\x00"
    yield "payload not a byte string", arr([tag(24, payload()), uint(zlib.crc32(payload()))])
    yield "root of 27 bytes", address(payload(root=ROOT[:27]))
    yield "type 1", address(payload(kind=1))
    yield "type 3", address(payload(kind=3))
    yield "payload of two items", address(arr([bstr(ROOT), cmap([])]))
    yield "byte after the payload", address(payload() + b"\x00")
    yield "attribute keys descending", address(payload(attributes=cmap([(uint(5), bstr(b"\x01")), (uint(2), bstr(uint(MAGIC)))])))
    yield "network magic in eight bytes", address(payload(attributes=magic_attr(inner_width=8)))
    yield "network magic over 32 bits", address(payload(attributes=magic_attr(value=1 << 32)))
    yield "empty", b""


def text(non_avvm, avvm=None):
    return json.dumps({"avvmDistr": avvm or {}, "nonAvvmBalances": non_avvm}, separators=(",", ":"), sort_keys=True, ensure_ascii=False)


REDEEM_KEY = base64.urlsafe_b64encode(bytes(range(32))).decode()


def cases():
    out = []
    for name, raw in addresses():
        out.append({"name": f"address: {name}", "text": text({b58(raw): "1000"})})
    out.append({"name": "address: not Base58 (0)", "text": text({"0" + b58(address()): "1000"})})
    out.append({"name": "address: not Base58 (l)", "text": text({b58(address()) + "l": "1000"})})
    out.append({"name": "address: leading 1 is a zero byte", "text": text({"1" + b58(address()): "1000"})})
    out.append({"name": "address: empty key", "text": text({"": "1000"})})
    out.append({"name": "two encodings of one address",
                "text": text({b58(address()): "1000", b58(address(crc_width=8)): "2000"})})
    a, b = b58(address()), b58(address(crc_width=8))
    for first, second, label in ((a, b, "canonical first"), (b, a, "canonical last")):
        out.append({"name": f"two encodings of one address in the text, {label}",
                    "text": '{"avvmDistr":{},"nonAvvmBalances":{"%s":"1000","%s":"2000"}}' % (first, second)})
    out.append({"name": "two addresses", "text": text({b58(address()): "1000", b58(address(payload(kind=2))): "2000"})})
    key = bytes(range(32))
    for label, k in (("padded base64url", base64.urlsafe_b64encode(key).decode()),
                     ("unpadded base64url", base64.urlsafe_b64encode(key).decode().rstrip("=")),
                     ("standard base64 alphabet", base64.b64encode(bytes([0xfb, 0xff] * 16)).decode()),
                     ("url alphabet", base64.urlsafe_b64encode(bytes([0xfb, 0xff] * 16)).decode()),
                     ("31 bytes", base64.urlsafe_b64encode(key[:31]).decode()),
                     ("33 bytes", base64.urlsafe_b64encode(key + b"\0").decode()),
                     ("nonzero padding bits", base64.urlsafe_b64encode(key).decode()[:42] + "x="),
                     ("padding in the middle", "AAAA=" + base64.urlsafe_b64encode(key).decode()[5:]),
                     ("two padding characters", base64.urlsafe_b64encode(key).decode()[:43] + "=="),
                     ("not base64", "!" * 44),
                     ("empty", "")):
        out.append({"name": f"AVVM key: {label}", "text": text({}, {k: "1000"})})
    url, std = base64.urlsafe_b64encode(bytes([0xfb, 0xff] * 16)).decode(), base64.b64encode(bytes([0xfb, 0xff] * 16)).decode()
    for first, second, label in ((url, std, "url first"), (std, url, "standard first")):
        out.append({"name": f"AVVM key: two spellings of one key, {label}",
                    "text": '{"avvmDistr":{"%s":"1000","%s":"2000"},"nonAvvmBalances":{}}' % (first, second)})
    amounts = ["0", "45000000000000000", "45000000000000001", "18446744073709551615", "18446744073709551616",
               "18446744073709551617", "-1", "-0", "+5", " 5", "5 ", "(5)", "( 5 )", "((5))", "- 5", "(-5)",
               "-(5)", "0x10", "0X1f", "0o17", "0O17", "0b101", "007", "5.0", "5e3", "5E0", "", "five", "5_000",
               "1" + "0" * 30, " 5", " 5"]
    for amount in amounts:
        out.append({"name": f"non-AVVM amount {json.dumps(amount)}", "text": text({b58(address()): amount})})
        out.append({"name": f"AVVM amount {json.dumps(amount)}", "text": text({}, {REDEEM_KEY: amount})})
    out.append({"name": "non-AVVM amount a JSON number", "text": '{"avvmDistr":{},"nonAvvmBalances":{"%s":1000}}' % b58(address())})
    return out


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--inputs", type=Path, required=True)
    p.add_argument("--reference", type=Path)
    p.add_argument("--out", type=Path)
    a = p.parse_args()
    cs = cases()
    names = [c["name"] for c in cs]
    assert len(set(names)) == len(names), "case names are unique"
    if a.reference is None:
        a.inputs.write_text(json.dumps(cs, indent=1) + "\n")
        return
    verdicts = {r["name"]: r["reference"] for r in json.loads(a.reference.read_text())}
    with a.out.open("w") as f:
        for c in cs:
            v = verdicts[c["name"]]
            row = dict(c, **v)
            if not v["accepted"]:
                row["refusal"] = refusal(v["failure"])
            f.write(json.dumps(row, sort_keys=True, ensure_ascii=False) + "\n")


def refusal(failure):
    """The field a refusal names, from the reader's `SchemaError` (its `seExpected`), in the shape
    circe derives for the suite's `Refusal`; a redeem key's wrong length keeps the length."""
    expected = re.search(r'seExpected = "([A-Za-z0-9]+)"', failure)
    assert expected, failure
    kind = expected.group(1)
    if kind == "Address":
        return {"Address": {}}
    if kind == "Word64":
        return {"Amount": {}}
    assert kind == "RedeemVerificationKey", failure
    length = re.search(r"Address length is (\d+)", failure)
    if length:
        return {"AvvmKeyLength": {"length": int(length.group(1))}}
    assert "is not base64(url) format" in failure, failure
    return {"AvvmKeyFormat": {}}


if __name__ == "__main__":
    main()
