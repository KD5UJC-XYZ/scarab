#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Build Plutus phase-2 test cases (fixtures/preview/plutus/*.json) from captured Preview blocks.

    preview_plutus_cases.py <blocks.hex> <epoch-params.json> <out-dir>

For every transaction of the blocks that runs a Plutus script, the spent and referenced outputs are
fetched from Koios (`utxo_info`, which also answers for spent outputs), re-encoded as outputs, and
written with the transaction's own encodings, the cost models of the block's epoch and the slot clock
the validity interval needs. `follow --plutus-cases` writes the same format from a running node.
"""
import hashlib
import json
import pathlib
import sys
import urllib.request

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
from devnet.encoding import bech32_bytes  # noqa: E402

KOIOS = "https://preview.koios.rest/api/v1/"
SYSTEM_START_MS = 1666656000000
EPOCH_SLOTS = 86400


# ---------------------------------------------------------------- CBOR (decoding with offsets)

def head(b, i):
    ib = b[i]
    major, ai = ib >> 5, ib & 31
    i += 1
    if ai < 24:
        return major, ai, i
    width = {24: 1, 25: 2, 26: 4, 27: 8}.get(ai)
    if width is None:
        return major, None, i  # indefinite
    return major, int.from_bytes(b[i:i + width], "big"), i + width


def skip(b, i):
    major, n, i = head(b, i)
    if major in (0, 1, 7):
        return i
    if major in (2, 3):
        if n is None:
            while b[i] != 0xFF:
                i = skip(b, i)
            return i + 1
        return i + n
    if major == 6:
        return skip(b, i)
    count = n if major == 4 else (None if n is None else 2 * n)
    if count is None:
        while b[i] != 0xFF:
            i = skip(b, i)
        return i + 1
    for _ in range(count):
        i = skip(b, i)
    return i


def items(b, i):
    """Spans of the elements of the array at `i`."""
    _, n, i = head(b, i)
    out = []
    for _ in range(n):
        j = skip(b, i)
        out.append((i, j))
        i = j
    return out


def value(b, i):
    major, n, j = head(b, i)
    if major == 0:
        return n
    if major == 1:
        return -1 - n
    if major == 2:
        return bytes(b[j:j + n])
    if major == 3:
        return b[j:j + n].decode()
    if major == 4:
        out = []
        for _ in range(n):
            v, j = value2(b, j)
            out.append(v)
        return out
    if major == 5:
        out = {}
        for _ in range(n):
            k, j = value2(b, j)
            v, j = value2(b, j)
            out[k if not isinstance(k, list) else tuple(k)] = v
        return out
    if major == 6:
        return ("tag", n, value(b, j))
    return n


def value2(b, i):
    return value(b, i), skip(b, i)


def fields(b, i):
    """Offsets of the values of the map at `i`, by (integer) key; values are decoded on demand."""
    _, n, i = head(b, i)
    out = {}
    for _ in range(n):
        key, i = value2(b, i)
        out[key] = i
        i = skip(b, i)
    return out


# ---------------------------------------------------------------- CBOR (encoding)

def enc_head(major, n):
    if n < 24:
        return bytes([major << 5 | n])
    for ai, w in ((24, 1), (25, 2), (26, 4), (27, 8)):
        if n < 1 << (8 * w):
            return bytes([major << 5 | ai]) + n.to_bytes(w, "big")


def enc(x):
    if isinstance(x, int):
        return enc_head(0, x)
    if isinstance(x, bytes):
        return enc_head(2, len(x)) + x
    if isinstance(x, (list, tuple)) and not (len(x) == 3 and x[0] == "tag"):
        return enc_head(4, len(x)) + b"".join(enc(i) for i in x)
    if isinstance(x, tuple) and x[0] == "tag":
        return enc_head(6, x[1]) + enc(x[2])
    if isinstance(x, dict):
        return enc_head(5, len(x)) + b"".join(enc(k) + enc(v) for k, v in x.items())
    raise TypeError(type(x))


def koios(path, body):
    request = urllib.request.Request(KOIOS + path, data=json.dumps(body).encode(),
                                     headers={"accept": "application/json", "content-type": "application/json"})
    return json.load(urllib.request.urlopen(request, timeout=120))


TAGS = ["spend", "mint", "cert", "reward", "vote", "propose"]


def kind(b, ws, invalid):
    """A label from the redeemer purposes and the script languages of the witness set."""
    f = fields(b, ws)
    at = f[5]
    major, n, i = head(b, at)
    tags = set()
    for _ in range(n):
        if major == 5:  # {[tag, index]: [data, units]}
            tags.add(value(b, items(b, i)[0][0]))
            i = skip(b, skip(b, i))
        else:  # [tag, index, data, units]
            tags.add(value(b, items(b, i)[0][0]))
            i = skip(b, i)
    purposes = sorted(TAGS[t] for t in tags)
    languages = [n for key, n in ((3, "v1"), (6, "v2"), (7, "v3")) if key in f]
    return "conway-" + ",".join(purposes) + ("-" + "+".join(languages) if languages else "-ref") + (
        "-failed" if invalid else "-passed")


def output_cbor(u):
    script = u["reference_script"]
    address = bech32_bytes(u["address"])
    coin = int(u["value"])
    assets = {}
    for a in u["asset_list"] or []:
        assets.setdefault(bytes.fromhex(a["policy_id"]), {})[bytes.fromhex(a["asset_name"] or "")] = int(a["quantity"])
    val = coin if not assets else [coin, assets]
    if u["datum_hash"] is None and u["inline_datum"] is None and script is None:
        return enc([address, val])
    out = {0: address, 1: val}
    if u["inline_datum"] is not None:
        out[2] = [1, ("tag", 24, bytes.fromhex(u["inline_datum"]["bytes"]))]
    elif u["datum_hash"] is not None:
        out[2] = [0, bytes.fromhex(u["datum_hash"])]
    if script is not None:
        language = {"plutusV1": 1, "plutusV2": 2, "plutusV3": 3}[script["type"]]
        # Koios `bytes` is the script as the ledger stores it (CBOR bytes of the flat program)
        out[3] = ("tag", 24, enc([language, bytes.fromhex(script["bytes"])]))
    return enc(out)


def main(blocks_file, params_file, out_dir):
    params = json.load(open(params_file))
    written = 0
    seen = set()
    for line in open(blocks_file):
        b = bytes.fromhex(line.strip())
        # [era, [header, bodies, witnesses, auxiliary, invalid]]
        i = skip(b, 1)  # after the era tag
        _, _, inner = head(b, i)
        parts = []
        j = inner
        for _ in range(5):
            k = skip(b, j)
            parts.append((j, k))
            j = k
        header, bodies, wits, aux, invalid = parts
        invalid_ix = set(value(b, invalid[0]))
        header_items = items(b, header[0])
        header_body = items(b, header_items[0][0])
        slot = value(b, header_body[1][0])
        aux_map = {k: 1 for k in fields(b, aux[0])}
        for ix, ((s, e), (ws, we)) in enumerate(zip(items(b, bodies[0]), items(b, wits[0]))):
            body_raw, wit_raw = bytes(b[s:e]), bytes(b[ws:we])
            if 5 not in fields(b, ws):
                continue
            txid = hashlib.blake2b(body_raw, digest_size=32).hexdigest()
            if txid in seen:
                continue
            seen.add(txid)
            body_at = fields(b, s)
            body = {k: value(b, at) for k, at in body_at.items() if k in (0, 3, 8, 13, 18)}

            def unpack(field):
                v = body.get(field)
                if isinstance(v, tuple) and v and v[0] == "tag":
                    v = v[2]
                return [f"{t.hex()}#{n}" for t, n in (v or [])]
            spent, referenced, collateral = unpack(0), unpack(18), unpack(13)
            infos = {f"{u['tx_hash']}#{u['tx_index']}": u
                     for u in koios("utxo_info",
                                    {"_utxo_refs": spent + referenced + collateral, "_extended": True})}
            epoch = str(slot // EPOCH_SLOTS)
            models = params[epoch]["costModels"]
            clock = {}
            for field in (3, 8):
                if field in body:
                    clock[str(body[field])] = str(SYSTEM_START_MS + body[field] * 1000)
            aux_raw = None
            if ix in aux_map:
                # re-slice the auxiliary data of this transaction
                _, n, k = head(b, aux[0])
                for _ in range(n):
                    key, k2 = value2(b, k)
                    k3 = skip(b, k2)
                    if key == ix:
                        aux_raw = bytes(b[k2:k3])
                    k = k3
            case = {
                "name": f"slot{slot}-{txid[:8]}",
                "kind": kind(b, ws, ix in invalid_ix),
                "slot": str(slot),
                "protocolMajor": str(params[epoch]["protocolMajor"]),
                "isValid": ix not in invalid_ix,
                "outcome": "passed",
                "body": body_raw.hex(),
                "witnesses": wit_raw.hex(),
                "auxiliary": aux_raw.hex() if aux_raw else None,
                "spent": [{"in": r, "out": output_cbor(infos[r]).hex()} for r in spent],
                "references": [{"in": r, "out": output_cbor(infos[r]).hex()} for r in referenced],
                "collateral": [{"in": r, "out": output_cbor(infos[r]).hex()} for r in collateral],
                "costModels": {str(k): [str(v) for v in models[name]]
                               for k, name in ((0, "PlutusV1"), (1, "PlutusV2"), (2, "PlutusV3"))},
                "clock": clock,
            }
            path = f"{out_dir}/{case['name']}.json"
            json.dump(case, open(path, "w"))
            written += 1
            print(path)
    print(f"{written} cases")


if __name__ == "__main__":
    main(*sys.argv[1:4])
