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

from .cbor import Raw, Tag, enc, head

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


def base_address(pay_hash, stake_hash, network=0, pay_script=False, stake_script=False):
    """Base address; header types 0 key/key, 1 script/key, 2 key/script, 3 script/script."""
    kind = (1 if pay_script else 0) | (2 if stake_script else 0)
    return bytes([(kind << 4) | network]) + pay_hash + stake_hash


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


# ---------------------------------------------------------------- Conway governance and Plutus

def credential(h, script=False):
    return [1 if script else 0, h]


def drep_ref(kind, h=None, script=False):
    """DRep as certificates and delegations encode it: key/script credential, always-abstain (2) or
    always-no-confidence (3)."""
    return {"abstain": [2], "noconfidence": [3]}.get(kind) or [1 if script else 0, h]


def anchor(url="https://example.invalid/anchor", digest=None):
    return [url, digest or blake2b(url.encode())]


def gov_id(txid_hex, ix):
    return [bytes.fromhex(txid_hex), ix]


def reg_cert(cred_hash, deposit, script=False):  # 7: registration with a deposit
    return [7, credential(cred_hash, script), deposit]


def unreg_cert(cred_hash, refund, script=False):  # 8
    return [8, credential(cred_hash, script), refund]


def vote_deleg_cert(cred_hash, drep, script=False):  # 9
    return [9, credential(cred_hash, script), drep]


def stake_vote_deleg_cert(cred_hash, pool, drep):  # 10
    return [10, credential(cred_hash), pool, drep]


def reg_deleg_cert(cred_hash, pool, deposit):  # 11
    return [11, credential(cred_hash), pool, deposit]


def reg_vote_deleg_cert(cred_hash, drep, deposit):  # 12
    return [12, credential(cred_hash), drep, deposit]


def reg_stake_vote_deleg_cert(cred_hash, pool, drep, deposit):  # 13
    return [13, credential(cred_hash), pool, drep, deposit]


def auth_committee_cert(cold_hash, hot_hash, cold_script=False, hot_script=False):  # 14
    return [14, credential(cold_hash, cold_script), credential(hot_hash, hot_script)]


def resign_committee_cert(cold_hash, anchor_=None, script=False):  # 15
    return [15, credential(cold_hash, script), anchor_]


def drep_reg_cert(drep_hash, deposit, anchor_=None, script=False):  # 16
    return [16, credential(drep_hash, script), deposit, anchor_]


def drep_unreg_cert(drep_hash, refund, script=False):  # 17
    return [17, credential(drep_hash, script), refund]


def drep_update_cert(drep_hash, anchor_=None, script=False):  # 18
    return [18, credential(drep_hash, script), anchor_]


def proposal(deposit, return_account, action, anchor_=None):
    return [deposit, return_account, action, anchor_ or anchor()]


def info_action():
    return [6]


def treasury_withdrawal_action(withdrawals, policy=None):
    return [2, {k: v for k, v in withdrawals.items()}, policy]


def param_change_action(update, prev=None, policy=None):
    return [0, prev, update, policy]


def hard_fork_action(major, minor, prev=None):
    return [1, prev, [major, minor]]


def no_confidence_action(prev=None):
    return [3, prev]


def update_committee_action(added, removed=(), threshold=(2, 3), prev=None):
    """`added` maps a cold credential hash to its last epoch; `removed` lists cold credential hashes."""
    return [4, prev, Tag(258, [credential(h) for h in removed]), {tuple(credential(h)): e for h, e in added.items()},
            Tag(30, list(threshold))]


def constitution_action(url, digest, script=None, prev=None):
    return [5, prev, [anchor(url, digest), script]]


# votes: voter = (kind, hash) with kind in committee, drep, pool
def voter(kind, h, script=False):
    return {"committee": [1 if script else 0, h], "drep": [3 if script else 2, h], "pool": [4, h]}[kind]


VOTE = {"no": 0, "yes": 1, "abstain": 2}


def voting_procedures(votes):
    """`votes`: list of (voter, [(gov_action_id, 'yes'/'no'/'abstain')])."""
    return {tuple(v): {tuple(i): [VOTE[x], None] for i, x in cast} for v, cast in votes}


# Plutus: the smallest scripts, `\ctx -> ()`, as flat-encoded UPLC wrapped the way the ledger stores it
def plutus_always_succeeds(version=3):
    # program 1.1.0, (lam x (con unit ())): 01 01 00 | lam, const, unit type | padding
    flat = bytes.fromhex("0101002499")
    inner = enc(flat)  # the PlutusBinary: CBOR bytes of the flat encoding
    return inner


def plutus_script_hash(content, version=3):
    return blake2b(bytes([version]) + content, 28)


def cost_model_view(language, cost_model):
    """The language-views entry for the script data hash (`cost_model`: the parameters in order)."""
    if language == 1:  # PlutusV1: the key and the indefinite list are wrapped as byte strings
        ints = b"".join(enc(v) for v in cost_model)
        return enc(b"\x00"), enc(b"\x9f" + ints + b"\xff")
    return enc(language - 1), enc(list(cost_model))


def script_data_hash(redeemers_cbor, datums_cbor, models):
    """`models` maps a language (1, 2, 3 for Plutus V1, V2, V3) to its cost model; no models gives 0xa0."""
    views = sorted((cost_model_view(l, m) for l, m in models.items()), key=lambda kv: (len(kv[0]), kv[0]))
    body = b"\xa0" if not views else head(5, len(views)) + b"".join(k + v for k, v in views)
    return blake2b(redeemers_cbor + datums_cbor + body)


# ---------------------------------------------------------------- transactions

ERA_INDEX = {"shelley": 0, "allegra": 1, "mary": 2, "alonzo": 3, "babbage": 4, "conway": 5}
ERA_TYPE = {"shelley": "TxSignedShelley", "allegra": "Tx AllegraEra", "mary": "Tx MaryEra", "alonzo": "Tx AlonzoEra",
            "babbage": "Tx BabbageEra", "conway": "Tx ConwayEra"}


def value(coin, assets=None):
    """Lovelace plus optional {policy_hash: {asset_name: quantity}}."""
    if not assets:
        return coin
    return [coin, {p: dict(a) for p, a in assets.items()}]


def tx_out(era, address, coin, assets=None, datum_hash=None, inline_datum=None, script_ref=None,
           script_ref_plutus=None):
    """Legacy array form where it suffices, the map form (Babbage) for inline datums and reference scripts."""
    v = value(coin, assets)
    if ERA_INDEX[era] >= ERA_INDEX["babbage"] and (inline_datum is not None or script_ref is not None
                                                     or script_ref_plutus is not None):
        out = {0: address, 1: v}
        if inline_datum is not None:
            out[2] = [1, Tag(24, enc(inline_datum))]
        elif datum_hash is not None:
            out[2] = [0, datum_hash]
        if script_ref is not None:
            out[3] = Tag(24, enc([0, script_ref]))  # [0, native script]
        if script_ref_plutus is not None:
            out[3] = Tag(24, enc(list(script_ref_plutus)))  # [language, plutus script]
        return out
    return [address, v] + ([datum_hash] if datum_hash is not None else [])


def redeemers_cbor(era, redeemers):
    """The redeemers as the witness set holds them: the map form {[tag, index]: [data, [mem, steps]]} from
    Conway, the list form [[tag, index, data, [mem, steps]], ...] before. (The script data hash covers
    these very bytes, `enc(redeemers_cbor(...))`.)"""
    if ERA_INDEX[era] >= ERA_INDEX["conway"]:
        return {(t, i): [d, list(u)] for t, i, d, u in redeemers}
    return [[t, i, d, list(u)] for t, i, d, u in redeemers]


def build_tx(era, inputs, outputs, fee, signers, ttl=None, valid_from=None, certs=None, withdrawals=None,
             update=None, mint=None, native_scripts=(), collateral=None, required_signers=None,
             collateral_return=None, total_collateral=None, reference_inputs=None, metadata=None, network_id=None,
             is_valid=True, aux_scripts=(), votes=None, proposals=None, treasury=None, donation=None,
             plutus=None, redeemers=None, datums=None, script_data=None, aux_plutus=None):
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
    if aux_plutus and index < ERA_INDEX["alonzo"]:
        raise ValueError("Plutus auxiliary scripts require an Alonzo-or-later envelope")
    if aux_plutus and any(language not in (1, 2, 3) for language in aux_plutus):
        raise ValueError("auxiliary Plutus language must be 1, 2 or 3")
    if metadata is not None or aux_scripts or aux_plutus:
        if aux_plutus:
            fields = {0: metadata} if metadata is not None else {}
            if aux_scripts:
                fields[1] = list(aux_scripts)
            fields.update({language + 1: list(scripts) for language, scripts in aux_plutus.items()})
            aux = Tag(259, fields)
        elif aux_scripts and index >= ERA_INDEX["allegra"]:
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
    if script_data is not None:
        body[11] = script_data
    if votes:
        body[19] = votes
    if proposals:
        body[20] = Tag(258, proposals)
    if treasury is not None:
        body[21] = treasury
    if donation:
        body[22] = donation
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
    for language, key in ((1, 3), (2, 6), (3, 7)):
        if plutus and plutus.get(language):
            witnesses[key] = [Raw(enc(bytes_)) for bytes_ in plutus[language]]
    if datums:
        witnesses[4] = list(datums)
    if redeemers:
        witnesses[5] = redeemers_cbor(era, redeemers)
    witness_bytes = enc(witnesses)
    if index >= ERA_INDEX["alonzo"]:
        tx = b"\x84" + body_bytes + witness_bytes + enc(is_valid) + enc(aux)
    else:
        tx = b"\x83" + body_bytes + witness_bytes + enc(aux)
    return tx, body_hash.hex()


def envelope(era, tx):
    return json.dumps({"type": ERA_TYPE[era], "description": "", "cborHex": tx.hex()})
