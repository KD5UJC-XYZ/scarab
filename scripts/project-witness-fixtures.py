#!/usr/bin/env python3
"""Offline public-input Ed25519 corpus projection, with immutable source pins.

No native loading, subprocess execution, network access, key generation or signing.
The optional --sodium-source reads but never runs a separately acquired upstream
sign.c; its pinned SHA256 is checked BEFORE decoding/parsing any source bytes.
"""
import argparse
from dataclasses import dataclass
import hashlib
import json
from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[1]
BASE = ROOT / "fixtures/witness"
SOURCE_SIGN_SHA256 = "fa3f37d292782694d721a218d0adf2b7de633552d41bbdc1d0ae0a708e32a21d"
VECTORS_SHA256 = "f79f0a87efbd99ebe159c18211497f6b0d282e7946f1839216dc31bbf99ef918"
L = 2**252 + 27742317777372353535851937790883648493
MAX_BYTES = 8_000_000

PINNED_INPUTS = {'README.md': 'ddc7f5006e9ff940da05a462a0fb8ba1cbf914f258d935c9d959fb55080beb34',
 'archive/StrictVerify.scala': 'b718a13959d1104a0e653ab752ab9d4b1a26d99fd7ef8f6a080d017d3aeb0605',
 'archive/Verify.scala': '1a3583f08dbf87d89bc07c688ca74f3f23dae738faca2f87a746f47632d392d2',
 'archive/bc-results.tsv': '2343a0aaaeddde496d9ce58c46bcbb43cf0d42ff2f523b7a3e89d60f858a9d92',
 'archive/pinned-source-oracle/provenance.json': '59e5963c74173151eec575b4faa0fda71ad6e7caa59e768dfbcb3e3da0c0a57e',
 'archive/pinned-source-oracle/record_provenance.py': '4625327e932d7951d1669815ab72c209efaedd1d31576e0862fba67ad5439e02',
 'archive/pinned-source-oracle/report.md': 'd33fa883c330e81196a28323f8fa7fb3520ac40fc69aea4c18a972ea03479c96',
 'archive/pinned-source-oracle/results.json': 'af45f40dcfe8e6bb670904c35067b3f581c0d7bdf6b7b63a8599eb4a1cfaf7d3',
 'archive/pinned-source-oracle/sha256.json': '263705beb788bbd2804e54ddca0688a87dab05ad26402e982b72e970d623ca1f',
 'archive/pinned-source-oracle/source-sha256.json': '0f683b9dbfc6d62c8a62d1bf86fee32ec341bb90fe97f63674f17a684736c936',
 'archive/pinned-source-oracle/summary.json': 'e5479f49ef77bc88867782d1eaef266c1c9f711cbd853a79abe754ad1e96da44',
 'archive/pinned-source-oracle/verify-compile-command.txt': '5851fdcd23af3f6b09c2b378e54c2f4d6d30cd20d0ed994eb7554436b7674c8f',
 'archive/pinned-source-oracle/verify-preprocessor-macros.txt': '549c031f12ff252718d5f668cabeed8de53f8c810df441635a2b62e7bd634f95',
 'archive/pinned-source-oracle/verify_public.py': 'd186571652e645d81fbb98290a28bf2c76c4dc73b51a169bb2562a0a4534e514',
 'archive/strict-results.tsv': '8a56009372dbf3efeb7ae0c042c81b58fe55e3a9506921214668063750af4430',
 'archive/system-sodium-results.tsv': '0e43a064aec030a12d8e4ed94f60542a6100fd4a623bfce53a283b2ff6969dde',
 'inputs/mary.cbor': 'e4229e5189a03c0489b3f64ece510812a61cd3a3853ee50e19f5587965385ab2',
 'inputs/shelley.cbor': 'e29fa93400521ce6b15b357a25ed61afdc81e8fd18cd15ebbc29f16e30019123',
 'inputs/sodium-public.json': 'f87e83f0f22dc39b89cd2657f270f381ad629c6ab06459294ed9a8a466ea4653',
 'inputs/speccheck-cases.json': '08e47a36d9aead288664930505584f353fff113ab854f2800db1e4f5b3540450',
 'inputs/wycheproof-ed25519.json': '752d2ea7d7c6cf4736381b6cbacb61f8182b126ab7cd9b058f00c50084975536',
 'licenses/amaru-LICENSE': '59899c6091b540582ed617e8eeaac4919dc985ccfc35459ee9752b699be5205b',
 'licenses/blueprint-conformance-LICENSE': '5bdd73433593173b55328fdc4cc01e826597e73cf52f93388a0ee786d39067cf',
 'licenses/bouncycastle-LICENSE.md': '0e01f1549c9022f406392ac2947d32223b7c2e977d21ea2f8c182fdeb4dae5fd',
 'licenses/cardano-base-LICENSE': '69ce94606a661fa3290eeaca21f3f9ad7d73dc985ffa346a1104704548f72d93',
 'licenses/cardano-ledger-conformance-LICENSE': '0d542e0c8804e39aa7f37eb00da5a762149dc682d7829451287e11b938e94594',
 'licenses/cardano-ledger-conformance-NOTICE': '58721f8b6ca67f0fcbe1cd739b384fec3126a35f7d79951aedaa2bc3863a3162',
 'licenses/cardano-node-LICENSE': '0d542e0c8804e39aa7f37eb00da5a762149dc682d7829451287e11b938e94594',
 'licenses/elisabeth-LICENSE': 'f8c5d5965fdae0556691dc621e84ca0c4c526ebd748a3f993f03116ff9f94c2f',
 'licenses/sodium-LICENSE': 'dea1855c9809f3faf22aa4a1fba20ec8af5a5587f23115012e5b98279cedc4af',
 'licenses/speccheck-LICENSE': 'c71d239df91726fc519c6eb72d318ec65820627232b2f796219e87dcf35d0ab4',
 'licenses/wycheproof-LICENSE': '58d1e17ffe5109a7ae296caafcadfdbe6a7d176f0bc4ab01e12a689b0499d8bd',
 'manifest.json': '8ad2594c25f4811f78c0fcb3288179e8cbe075aa8c35cc5572816881d24a1f91',
 'provenance/libsodium-1.0.18-open.c': '2b345bf78695844262832eb14d0655da3a815024db0d437791142ee3b34b062d',
 'provenance/node-flake.lock': '6e2682f1ab84e0f87722dceea68d9582a1a96a21054ffc7eb75b9e415788c5e2',
 'provenance/node-release.json': '91f6a9efb2e279f32633c4e98b6a57a7bbb90e44a70fb36fb0b8c61281234197',
 'provenance/sodium-open.c': '2b345bf78695844262832eb14d0655da3a815024db0d437791142ee3b34b062d',
 'provenance/speccheck-historical-results.md': '308324876f18d49c5c0a58446a87df96dd6a2ba82f6730ef3fee458b8329b172'}

EXPECTED_OUTPUTS = {'ledger-witnesses.json': 'a0876770218e9f947b19c47be72ff26ca741a1148fd375cc573f3078aacdf4c8',
 'ledger-witnesses.tsv': 'eb8a8ae0c0731ef24e03789dcc26c314cdada4298a6555aa69f484ff544c2d3f',
 'projection.json': '3b5343cf39249fa96ce4e1182a47697e993b28dba498763b255623c810b226e7',
 'status.tsv': 'f5b22e990b28f1bff90ef2304f3a1d19e1ba40d85e984bcf08a869cffbf44934',
 'vectors.tsv': 'f79f0a87efbd99ebe159c18211497f6b0d282e7946f1839216dc31bbf99ef918'}


def require(condition, message):
    if not condition:
        raise ValueError(message)


def bounded_read(path, maximum=MAX_BYTES):
    # Read at most limit+1, including files that change while being read.
    with path.open("rb") as stream:
        data = stream.read(maximum + 1)
    require(len(data) <= maximum, "file exceeds byte limit: " + str(path))
    return data


def pinned_read(path, expected, maximum=MAX_BYTES):
    data = bounded_read(path, maximum)
    require(hashlib.sha256(data).hexdigest() == expected, "SHA256 mismatch: " + str(path))
    return data


def json_bytes(value):
    return (json.dumps(value, indent=2, sort_keys=True) + "\n").encode("utf-8")


def read_inputs():
    # Validate every source and archive before interpreting any of them.
    return {name: pinned_read(BASE / name, expected) for name, expected in PINNED_INPUTS.items()}


def project_sodium_source(path):
    source = pinned_read(path, SOURCE_SIGN_SHA256, 3_000_000)
    pattern = rb'\{\{([^}]+)\},\{([^}]+)\},\{([^}]+)\},"([^"]*)"\}'
    projected = []
    for index, match in enumerate(re.finditer(pattern, source)):
        # The first field is a published secret seed. Never project or execute it.
        _, pk, signature, message = match.groups()
        def hex_array(value):
            return b"".join(re.findall(rb"0x([0-9a-f]{2})", value)).decode("ascii")
        projected.append({"index": index, "public_key_hex": hex_array(pk),
                          "signature_hex": hex_array(signature),
                          "message_hex": b"".join(re.findall(rb"\\x([0-9a-f]{2})", message)).decode("ascii")})
    require(len(projected) == 1024, "expected 1024 pinned sodium known answers")
    # Match the byte format of the vendored seed-free projection exactly.
    result = (json.dumps(projected, indent=2) + "\n").encode("utf-8")
    require(hashlib.sha256(result).hexdigest() == PINNED_INPUTS["inputs/sodium-public.json"],
            "public sodium projection mismatch")
    return result


@dataclass(frozen=True)
class Node:
    major: int
    value: object
    start: int
    end: int
    payload_start: int

    def identity(self):
        if self.major in (4, 5):
            v = tuple(n.identity() for n in self.value)
            if self.major == 5:
                v = frozenset(zip(v[::2], v[1::2]))
        elif self.major == 6:
            v = (self.value[0], self.value[1].identity())
        else:
            v = self.value
        return self.major, v


def decode(raw):
    """Bounded CBOR with semantic duplicate-map rejection, preserving source ranges.

    Indefinite arrays/maps are supported; all other indefinite forms, floating point,
    undefined/simple extensions, unknown tags, truncated items and trailing bytes fail.
    The additional-info discriminator is retained: a definite length of 31 is valid.
    """
    require(len(raw) <= 1_000_000, 'CBOR byte limit')
    count = 0

    def read(pos, depth=0):
        nonlocal count
        count += 1
        require(depth <= 64 and count <= 100_000, 'CBOR resource limit')
        require(pos < len(raw), 'truncated CBOR header')
        start = pos
        head = raw[pos]
        pos += 1
        major, ai = head >> 5, head & 31
        indefinite = ai == 31
        require(ai not in (28, 29, 30), 'reserved CBOR additional info')
        number = ai
        if ai in (24, 25, 26, 27):
            width = 1 << (ai - 24)
            require(pos + width <= len(raw), 'truncated CBOR argument')
            number = int.from_bytes(raw[pos:pos + width], 'big')
            pos += width
        payload_start = pos
        require(not indefinite or major in (4, 5), 'unsupported indefinite CBOR')
        if major in (0, 1):
            value = number if major == 0 else -1 - number
        elif major in (2, 3):
            require(pos + number <= len(raw), 'truncated CBOR string')
            value = raw[pos:pos + number]
            if major == 3:
                value.decode('utf-8', errors='strict')
            pos += number
        elif major in (4, 5):
            value = []
            if indefinite:
                while True:
                    require(pos < len(raw), 'missing CBOR break')
                    if raw[pos] == 255:
                        pos += 1
                        break
                    child, pos = read(pos, depth + 1)
                    value.append(child)
            else:
                n = number * (2 if major == 5 else 1)
                require(n <= 100_000, 'CBOR container limit')
                for _ in range(n):
                    child, pos = read(pos, depth + 1)
                    value.append(child)
            if major == 5:
                require(len(value) % 2 == 0, 'odd CBOR map')
                keys = [n.identity() for n in value[::2]]
                require(len(keys) == len(set(keys)), 'duplicate CBOR map key')
            value = tuple(value)
        elif major == 6:
            require(number in (30, 258), 'unknown CBOR tag')
            child, pos = read(pos, depth + 1)
            require(child.major == 4, 'tag requires array')
            if number == 30:
                require(len(child.value) == 2 and all(n.major in (0, 1) for n in child.value)
                        and child.value[1].value > 0, 'invalid rational tag')
            value = (number, child)
        elif major == 7:
            require(ai in (20, 21, 22), 'unsupported CBOR simple or floating point')
            value = ai
        else:
            raise ValueError('unsupported CBOR major')
        return Node(major, value, start, pos, payload_start), pos

    result, end = read(0)
    require(end == len(raw), 'trailing CBOR bytes')
    return result


def array(n, size=None):
    require(n.major == 4 and (size is None or len(n.value) == size), 'array shape')
    return n.value


def map_items(node):
    require(node.major == 5, "expected CBOR map")
    return list(zip(node.value[::2], node.value[1::2]))


def as_sequence(node):
    if node.major == 6:
        require(node.value[0] == 258, "expected set tag 258")
        node = node.value[1]
    return array(node)


def public_hex(value, expected_length=None):
    require(isinstance(value, str) and re.fullmatch(r"(?:[0-9a-f]{2})*", value) is not None,
            "invalid lower-case public hex")
    raw = bytes.fromhex(value)
    require(expected_length is None or len(raw) == expected_length, "public field length mismatch")
    return raw


def read_results(data, fields):
    rows = []
    for line in data.decode("ascii").splitlines():
        row = line.split("\t")
        require(len(row) in fields, "archival result shape")
        rows.append(row)
    require(len(rows) == 2219 and len({row[0] for row in rows}) == 2219, "archival result count/IDs")
    return {row[0]: row[1:] for row in rows}


def project(inputs):
    rows, details, envelopes = [], [], []
    sodium_results = read_results(inputs["archive/system-sodium-results.tsv"], (2,))
    bc_results = read_results(inputs["archive/bc-results.tsv"], (2, 4))
    strict_results = read_results(inputs["archive/strict-results.tsv"], (2,))

    def add(identifier, pk, signature, message, source_expected, kind, source):
        public_hex(pk, 32)
        public_hex(signature)
        public_hex(message)
        require(identifier not in {row[0] for row in rows}, "duplicate vector ID")
        require(source_expected in ("true", "false", "unknown"), "source expectation")
        malformed = len(signature) != 128
        native = sodium_results[identifier][0]
        bc = bc_results[identifier][0]
        strict = strict_results[identifier][0]
        if malformed:
            require(native == "NotRunMalformedLength" and bc == strict == "MalformedLength",
                    "malformed row archive semantics")
            expected = "MalformedSignatureLength"
        else:
            require(native in ("true", "false") and strict == native and bc in ("true", "false"),
                    "fixed-size archive semantics")
            expected = "SignatureVerified" if native == "true" else "SignatureRejected"
            require(source_expected == "unknown" or source_expected == native,
                    "upstream expectation disagrees with archived strict/native result")
        rows.append((identifier, pk, signature, message))
        details.append({"id": identifier, "source_expected": source_expected,
                        "expected_kind": kind, "expected_result": expected,
                        "system_sodium": native, "bc_raw": bc, "strict_archived": strict,
                        "bc_full_subgroup_public_key": bc_results[identifier][1] if not malformed else None,
                        "bc_full_subgroup_R": bc_results[identifier][2] if not malformed else None,
                        "source": source})

    sodium = json.loads(inputs["inputs/sodium-public.json"])
    require(len(sodium) == 1024, "sodium projection count")
    for index, row in enumerate(sodium):
        require(set(row) == {"index", "public_key_hex", "signature_hex", "message_hex"},
                "sodium public-only projection fields")
        require(row["index"] == index and len(public_hex(row["message_hex"])) == index,
                "sodium source index/message length")
        pk, signature, message = row["public_key_hex"], row["signature_hex"], row["message_hex"]
        public_hex(signature, 64)
        source = {"input": "inputs/sodium-public.json", "case_index": index,
                  "original_source_sha256": SOURCE_SIGN_SHA256}
        add(f"sodium-valid-{index}", pk, signature, message, "true", "upstream-sodium-known-answer", source)
        modified = signature[:64] + (int.from_bytes(bytes.fromhex(signature[64:]), "little") + L).to_bytes(32, "little").hex()
        add(f"sodium-S-plus-L-{index}", pk, modified, message, "false",
            "upstream-sodium-non-COMPAT-S-plus-L", dict(source, mutation="S + L as upstream add_l"))

    speccheck = json.loads(inputs["inputs/speccheck-cases.json"])
    require(len(speccheck) == 12, "speccheck count")
    for index, row in enumerate(speccheck):
        require(set(row) == {"pub_key", "signature", "message"}, "speccheck public-only fields")
        add(f"speccheck-{index}", row["pub_key"], row["signature"], row["message"], "unknown",
            "published-adversarial-target-observation",
            {"input": "inputs/speccheck-cases.json", "case_index": index,
             "expectation_note": "No universal signature verdict in cases.json; archived target observations are separate."})

    wycheproof = json.loads(inputs["inputs/wycheproof-ed25519.json"])
    require(wycheproof["numberOfTests"] == 151, "Wycheproof declared count")
    count = 0
    for group_index, group in enumerate(wycheproof["testGroups"]):
        for test in group["tests"]:
            require(test["result"] in ("valid", "invalid"), "unexpected Wycheproof result")
            count += 1
            add(f"wycheproof-{test['tcId']}", group["publicKey"]["pk"], test["sig"], test["msg"],
                "true" if test["result"] == "valid" else "false", "upstream-wycheproof-" + test["result"],
                {"input": "inputs/wycheproof-ed25519.json", "group_index": group_index,
                 "tcId": test["tcId"], "result": test["result"], "flags": test["flags"], "comment": test["comment"]})
    require(count == 151, "Wycheproof actual count")

    for sequence, original_name, suite in (("shelley", "amaru-shelley-fixture", "ShelleyImpSpec"),
                                           ("mary", "amaru-value-fixture", "MaryImpSpec")):
        input_name = f"inputs/{sequence}.cbor"
        raw = inputs[input_name]
        root = array(decode(raw))
        events = array(root[3])
        for event_index, event in enumerate(events):
            event_values = array(event)
            if event_values[0].major != 0 or event_values[0].value != 0:
                continue
            require(len(event_values) == 4 and event_values[1].major == 2, "ledger event shape")
            tx_node = event_values[1]
            tx = tx_node.value
            tx_fields = array(decode(tx), 4)
            body = tx_fields[0]
            require(body.major == 5, "body map shape")
            body_bytes = tx[body.start:body.end]
            body_hash = hashlib.blake2b(body_bytes, digest_size=32).hexdigest()
            require(event_values[2].major == 7 and event_values[2].value in (20, 21), "ledger expected boolean")
            ledger_accepted = event_values[2].value == 21
            witness_map = {key.value: value for key, value in map_items(tx_fields[1]) if key.major == 0}
            witnesses = as_sequence(witness_map[0]) if 0 in witness_map else ()
            for witness_index, witness in enumerate(witnesses):
                pk, signature = array(witness, 2)
                require(pk.major == signature.major == 2, "vkey witness byte strings")
                public_hex(pk.value.hex(), 32)
                public_hex(signature.value.hex(), 64)
                identifier = f"{original_name}-event-{event_index}-witness-{witness_index}"
                kind = "accepted-ledger-setup-witness" if ledger_accepted else "rejected-ledger-transaction-signature-observation"
                source = {
                    "input": input_name, "source_sha256": PINNED_INPUTS[input_name],
                    "archive_member": f"eras/conway/impl/dump/Conway/Imp/{suite}/UTXO/ShelleyUtxoPredFailure/ValueNotConservedUTxO",
                    "event_index": event_index, "witness_index": witness_index,
                    "source_ledger_expected_success": ledger_accepted,
                    "signature_expected_source": ("Signature expectation supported by accepted Haskell ledger setup transaction."
                                                  if ledger_accepted else "No separately stored Haskell signature golden; true is archived public-only differential observation, not the false transaction verdict."),
                    "event_range_in_source": [event.start, event.end],
                    "transaction_range_in_source": [tx_node.payload_start, tx_node.end],
                    "transaction_cbor_wrapper_range_in_source": [tx_node.start, tx_node.end],
                    "body_range_in_transaction": [body.start, body.end],
                    "body_range_in_source": [tx_node.payload_start + body.start, tx_node.payload_start + body.end],
                    "witness_range_in_transaction": [witness.start, witness.end],
                    "witness_range_in_source": [tx_node.payload_start + witness.start, tx_node.payload_start + witness.end],
                    "public_key_payload_range_in_transaction": [pk.payload_start, pk.end],
                    "signature_payload_range_in_transaction": [signature.payload_start, signature.end],
                    "public_key_payload_range_in_source": [tx_node.payload_start + pk.payload_start, tx_node.payload_start + pk.end],
                    "signature_payload_range_in_source": [tx_node.payload_start + signature.payload_start, tx_node.payload_start + signature.end],
                    "slot": str(event_values[3].value), "era": "Conway", "protocol_version": [9, 0]
                }
                add(identifier, pk.value.hex(), signature.value.hex(), body_hash,
                    "true" if ledger_accepted else "unknown", kind, source)
                envelopes.append(dict(source, id=identifier, tx_cbor_hex=tx.hex(), body_cbor_hex=body_bytes.hex(),
                                      body_hash_blake2b256=body_hash, public_key_hex=pk.value.hex(),
                                      signature_hex=signature.value.hex(), expected_witness_result="SignatureVerified"))

    require(len(rows) == 2219 and len(envelopes) == 8, "complete corpus count")
    require(sum(row["expected_result"] == "MalformedSignatureLength" for row in details) == 12, "malformed count")
    require(sum(row["source_ledger_expected_success"] for row in envelopes) == 3, "accepted ledger witness count")
    differences = [row["id"] for row in details if row["bc_raw"] != row["strict_archived"]]
    require(differences == ["speccheck-2", "speccheck-4", "speccheck-5"], "archived BC differential set")
    require(next(row for row in details if row["id"] == "speccheck-3")["expected_result"] == "SignatureVerified",
            "mixed-order compatibility control")
    # New exact-source native observations are independent, additional evidence.
    # Preserve historical status.tsv unchanged rather than relabeling its oracle.
    pinned_native = json.loads(inputs["archive/pinned-source-oracle/results.json"])
    require(len(pinned_native) == 2219, "pinned-source native result count")
    for row, status, native_row in zip(rows, details, pinned_native):
        require(native_row["id"] == row[0], "pinned-source native result order")
        require(native_row["public_key_bytes"] == len(bytes.fromhex(row[1])) and
                native_row["signature_bytes"] == len(bytes.fromhex(row[2])) and
                native_row["message_bytes"] == len(bytes.fromhex(row[3])), "pinned-source public input lengths")
        malformed = status["expected_result"] == "MalformedSignatureLength"
        require(native_row["native_called"] == (not malformed), "pinned-source native ABI boundary")
        if malformed:
            require(native_row["pinned_sodium"] is None and native_row["native_rc"] is None,
                    "pinned-source malformed result must be absent")
        else:
            expected_native = status["system_sodium"] == "true"
            require(native_row["pinned_sodium"] == native_row["system_sodium"] ==
                    native_row["strict_prototype"] == expected_native,
                    "pinned-source differential disagreement")
            require(native_row["bc"] == (status["bc_raw"] == "true"), "pinned-source BC observation")
            require(native_row["native_rc"] == (0 if expected_native else -1), "pinned-source return code")
    summary = json.loads(inputs["archive/pinned-source-oracle/summary.json"])
    require(summary == {"total": 2219, "native_calls": 2207, "malformed_excluded": 12,
                        "ledger_witnesses": 8, "ledger_verified": 8, "version": "1.0.18",
                        "system_divergences": [], "strict_divergences": [],
                        "bc_divergences": ["speccheck-2", "speccheck-4", "speccheck-5"],
                        "accepted": 1121, "rejected": 1086}, "pinned-source summary")
    vectors = ("\n".join("\t".join(row) for row in rows) + "\n").encode("ascii")
    require(hashlib.sha256(vectors).hexdigest() == VECTORS_SHA256, "fixed vectors.tsv digest")
    columns = ("id", "source_expected", "expected_kind", "expected_result", "system_sodium", "bc_raw", "strict_archived")
    status = ("\t".join(columns) + "\n" + "".join("\t".join(row[key] for key in columns) + "\n" for row in details)).encode("ascii")
    ledger_tsv = "".join("\t".join((row["id"], row["tx_cbor_hex"], str(row["witness_index"]),
                                    row["body_cbor_hex"], row["body_hash_blake2b256"])) + "\n" for row in envelopes).encode("ascii")
    return {"vectors.tsv": vectors, "status.tsv": status, "projection.json": json_bytes(details),
            "ledger-witnesses.json": json_bytes(envelopes), "ledger-witnesses.tsv": ledger_tsv}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true", help="verify reproducibility and hashes without writing")
    parser.add_argument("--sodium-source", type=Path,
                        help="verify separately acquired original sign.c and its public-only projection; never executes source")
    args = parser.parse_args()
    inputs = read_inputs()
    if args.sodium_source is not None:
        require(project_sodium_source(args.sodium_source) == inputs["inputs/sodium-public.json"],
                "source-to-public-projection mismatch")
    outputs = project(inputs)
    known = set(PINNED_INPUTS) | set(EXPECTED_OUTPUTS) | {"SHA256SUMS"}
    actual = {str(path.relative_to(BASE)) for path in BASE.rglob("*") if path.is_file()}
    require(actual <= known, "unrecognized fixture file; never vendor secret-bearing upstream tables")
    for name, data in outputs.items():
        require(hashlib.sha256(data).hexdigest() == EXPECTED_OUTPUTS[name], "fixed output SHA256 mismatch: " + name)
        if args.check:
            require(bounded_read(BASE / name) == data, "derived artifact mismatch: " + name)
        else:
            (BASE / name).write_bytes(data)
    files = {**inputs, **outputs}
    sums = "".join(hashlib.sha256(data).hexdigest() + "  " + name + "\n" for name, data in sorted(files.items())).encode("ascii")
    if args.check:
        require(bounded_read(BASE / "SHA256SUMS") == sums, "SHA256SUMS mismatch")
    else:
        (BASE / "SHA256SUMS").write_bytes(sums)
    print("Verified 2219 public vectors; 2207 fixed-size archival comparisons; 12 malformed signatures; 8 ledger witnesses.")
    if args.sodium_source is not None:
        print("Verified immutable original sign.c SHA256 before extraction; projected only public fields.")


if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError, KeyError, TypeError, json.JSONDecodeError) as error:
        print("witness projection failed: " + str(error), file=sys.stderr)
        sys.exit(1)
