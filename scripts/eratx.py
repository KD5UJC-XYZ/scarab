# SPDX-License-Identifier: Apache-2.0
"""Pre-Conway transaction construction for the era devnet (stdlib only).

cardano-cli 11.x can only build Conway transactions, and `compatible <era>` offers just inputs,
outputs, certificates and update proposals. The e2e load needs withdrawals, validity intervals,
native scripts, minting, collateral and Babbage outputs in the older eras too, so this module
encodes the transaction CBOR itself and signs with a small Ed25519. The reference node still
validates every transaction, which is the point: it is the oracle for the Scala ledger.
"""
import hashlib
import json
import struct

# ---------------------------------------------------------------- CBOR


class Tag:
    def __init__(self, tag, value):
        self.tag, self.value = tag, value


class Raw:
    """Already encoded CBOR."""

    def __init__(self, data):
        self.data = data


def _head(major, n):
    if n < 24:
        return bytes([major << 5 | n])
    if n < 1 << 8:
        return bytes([major << 5 | 24, n])
    if n < 1 << 16:
        return bytes([major << 5 | 25]) + struct.pack(">H", n)
    if n < 1 << 32:
        return bytes([major << 5 | 26]) + struct.pack(">I", n)
    return bytes([major << 5 | 27]) + struct.pack(">Q", n)


def enc(x):
    if isinstance(x, Raw):
        return x.data
    if x is None:
        return b"\xf6"
    if x is True:
        return b"\xf5"
    if x is False:
        return b"\xf4"
    if isinstance(x, int):
        return _head(0, x) if x >= 0 else _head(1, -1 - x)
    if isinstance(x, (bytes, bytearray)):
        return _head(2, len(x)) + bytes(x)
    if isinstance(x, str):
        b = x.encode()
        return _head(3, len(b)) + b
    if isinstance(x, (list, tuple)):
        return _head(4, len(x)) + b"".join(enc(i) for i in x)
    if isinstance(x, dict):
        items = sorted(((enc(k), enc(v)) for k, v in x.items()), key=lambda kv: (len(kv[0]), kv[0]))
        return _head(5, len(items)) + b"".join(k + v for k, v in items)
    if isinstance(x, Tag):
        return _head(6, x.tag) + enc(x.value)
    raise TypeError(type(x))


# ---------------------------------------------------------------- hashes and Ed25519 (RFC 8032)

def blake2b(data, size=32):
    return hashlib.blake2b(data, digest_size=size).digest()


_P = 2 ** 255 - 19
_Q = 2 ** 252 + 27742317777372353535851937790883648493
_D = -121665 * pow(121666, -1, _P) % _P
_I = pow(2, (_P - 1) // 4, _P)


def _add(a, b):
    (x1, y1, z1, t1), (x2, y2, z2, t2) = a, b
    A, B = (y1 - x1) * (y2 - x2) % _P, (y1 + x1) * (y2 + x2) % _P
    C, D = t1 * 2 * _D * t2 % _P, z1 * 2 * z2 % _P
    E, F, G, H = B - A, D - C, D + C, B + A
    return (E * F % _P, G * H % _P, F * G % _P, E * H % _P)


def _mul(s, p):
    q = (0, 1, 1, 0)
    while s:
        if s & 1:
            q = _add(q, p)
        p = _add(p, p)
        s >>= 1
    return q


def _recover_x(y, sign):
    x2 = (y * y - 1) * pow(_D * y * y + 1, -1, _P) % _P
    x = pow(x2, (_P + 3) // 8, _P)
    if (x * x - x2) % _P:
        x = x * _I % _P
    return _P - x if (x & 1) != sign else x


_GY = 4 * pow(5, -1, _P) % _P
_GX = _recover_x(_GY, 0)
_G = (_GX, _GY, 1, _GX * _GY % _P)


def _compress(p):
    zi = pow(p[2], -1, _P)
    x, y = p[0] * zi % _P, p[1] * zi % _P
    return int.to_bytes(y | ((x & 1) << 255), 32, "little")


def _expand(seed):
    h = hashlib.sha512(seed).digest()
    a = int.from_bytes(h[:32], "little")
    a &= (1 << 254) - 8
    a |= 1 << 254
    return a, h[32:]


def public_key(seed):
    return _compress(_mul(_expand(seed)[0], _G))


def sign(seed, msg):
    a, prefix = _expand(seed)
    A = _compress(_mul(a, _G))
    r = int.from_bytes(hashlib.sha512(prefix + msg).digest(), "little") % _Q
    R = _compress(_mul(r, _G))
    k = int.from_bytes(hashlib.sha512(R + A + msg).digest(), "little") % _Q
    return R + int.to_bytes((r + k * a) % _Q, 32, "little")


# ---------------------------------------------------------------- keys and addresses

class Key:
    """A payment/stake/pool signing key read from a cardano-cli text envelope (32-byte seed)."""

    def __init__(self, skey_json):
        cbor = bytes.fromhex(json.loads(skey_json)["cborHex"])
        if cbor[:2] != b"\x58\x20":
            raise ValueError("only plain 32-byte Ed25519 signing keys are supported")
        self.seed = cbor[2:]
        self.vkey = public_key(self.seed)
        self.hash = blake2b(self.vkey, 28)

    def witness(self, body_hash):
        return [self.vkey, sign(self.seed, body_hash)]


def base_address(pay_hash, stake_hash, network=0):
    return bytes([network]) + pay_hash + stake_hash


def enterprise_address(pay_hash, network=0, script=False):
    return bytes([(0x70 if script else 0x60) | network]) + pay_hash


def reward_address(stake_hash, network=0, script=False):
    return bytes([(0xf0 if script else 0xe0) | network]) + stake_hash


# ---------------------------------------------------------------- native scripts

def sig_script(key_hash):
    return [0, key_hash]


def all_script(*s):
    return [1, list(s)]


def any_script(*s):
    return [2, list(s)]


def n_of_script(n, *s):
    return [3, n, list(s)]


def after_script(slot):  # invalid_before
    return [4, slot]


def before_script(slot):  # invalid_hereafter
    return [5, slot]


def script_hash(native):
    return blake2b(b"\x00" + enc(native), 28)


# ---------------------------------------------------------------- certificates

def stake_key_cert(tag, stake_hash):  # 0 registration, 1 deregistration
    return [tag, [0, stake_hash]]


def delegation_cert(stake_hash, pool_hash):
    return [2, [0, stake_hash], pool_hash]


def pool_registration_cert(p):
    return [3, p["operator"], p["vrf"], p["pledge"], p["cost"], Tag(30, p["margin"]), p["reward_account"],
            p["owners"], p.get("relays", []), p.get("metadata")]


def pool_retirement_cert(pool_hash, epoch):
    return [4, pool_hash, epoch]


# ---------------------------------------------------------------- transactions

ERA_INDEX = {"shelley": 0, "allegra": 1, "mary": 2, "alonzo": 3, "babbage": 4, "conway": 5}
ERA_TYPE = {"shelley": "TxSignedShelley", "allegra": "Tx AllegraEra", "mary": "Tx MaryEra", "alonzo": "Tx AlonzoEra",
            "babbage": "Tx BabbageEra", "conway": "Tx ConwayEra"}


def value(coin, assets=None):
    """Lovelace plus optional {policy_hash: {asset_name: quantity}}."""
    if not assets:
        return coin
    return [coin, {p: dict(a) for p, a in assets.items()}]


def tx_out(era, address, coin, assets=None, datum_hash=None, inline_datum=None, script_ref=None):
    """Legacy array form where it suffices, the map form (Babbage) for inline datums and reference scripts."""
    v = value(coin, assets)
    if ERA_INDEX[era] >= ERA_INDEX["babbage"] and (inline_datum is not None or script_ref is not None):
        out = {0: address, 1: v}
        if inline_datum is not None:
            out[2] = [1, Tag(24, enc(inline_datum))]
        elif datum_hash is not None:
            out[2] = [0, datum_hash]
        if script_ref is not None:
            out[3] = Tag(24, enc([0, script_ref]))  # [0, native script]
        return out
    return [address, v] + ([datum_hash] if datum_hash is not None else [])


def build_tx(era, inputs, outputs, fee, signers, ttl=None, valid_from=None, certs=None, withdrawals=None,
             update=None, mint=None, native_scripts=(), collateral=None, required_signers=None,
             collateral_return=None, total_collateral=None, reference_inputs=None, metadata=None, network_id=None,
             is_valid=True, aux_scripts=()):
    """Signed transaction CBOR of any Shelley-based era. `inputs` are (txid_hex, ix); `signers` are Keys."""
    index = ERA_INDEX[era]
    body = {0: [[bytes.fromhex(t), i] for t, i in inputs], 1: outputs, 2: fee}
    if ttl is not None:
        body[3] = ttl
    if certs:
        body[4] = certs
    if withdrawals:
        body[5] = withdrawals
    if update is not None:
        body[6] = update
    aux = None
    if metadata is not None or aux_scripts:
        if aux_scripts and index >= ERA_INDEX["allegra"]:
            aux = [metadata or {}, list(aux_scripts)]
        else:
            aux = metadata or {}
        body[7] = blake2b(enc(aux))
    if valid_from is not None:
        body[8] = valid_from
    if mint:
        body[9] = mint
    if collateral:
        body[13] = [[bytes.fromhex(t), i] for t, i in collateral]
    if required_signers:
        body[14] = required_signers
    if network_id is not None:
        body[15] = network_id
    if collateral_return is not None:
        body[16] = collateral_return
    if total_collateral is not None:
        body[17] = total_collateral
    if reference_inputs:
        body[18] = [[bytes.fromhex(t), i] for t, i in reference_inputs]
    body_bytes = enc(body)
    body_hash = blake2b(body_bytes)
    witnesses = {}
    seen = set()
    vkeys = []
    for k in signers:
        if k.hash not in seen:
            seen.add(k.hash)
            vkeys.append(k.witness(body_hash))
    if vkeys:
        witnesses[0] = vkeys
    if native_scripts:
        witnesses[1] = list(native_scripts)
    witness_bytes = enc(witnesses)
    if index >= ERA_INDEX["alonzo"]:
        tx = b"\x84" + body_bytes + witness_bytes + enc(is_valid) + enc(aux)
    else:
        tx = b"\x83" + body_bytes + witness_bytes + enc(aux)
    return tx, body_hash.hex()


def envelope(era, tx):
    return json.dumps({"type": ERA_TYPE[era], "description": "", "cborHex": tx.hex()})
