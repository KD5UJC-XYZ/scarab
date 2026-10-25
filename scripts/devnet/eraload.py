# SPDX-License-Identifier: Apache-2.0
"""Era-appropriate transaction load for the era-walking devnet (see devnet.py `e2e`).

Every era gets the transaction types that era introduced, so the Scala ledger is exercised on the
rules of each era and compared with the reference node while the chain is still in that era:

  Shelley  transfers, stake key registration and delegation, a new pool, pool re-registration,
           genesis-delegate MIR and genesis delegation certificates, reward withdrawals
  Allegra  validity intervals, a timelock script address funded and spent, auxiliary data, a pool
           retirement
  Mary     minting and burning under native-script policies, multi-asset transfers
  Alonzo   collateral inputs, datum-hash outputs, required signers, network id
           Plutus V1 spends, minting and failed-script collateral
  Babbage  inline datums, reference-script outputs, reference inputs, collateral return; V1/V2 scripts

Boundary cases (devnet.py e2e runs three Shelley epochs, genesis minUTxOValue 1 ada, pool margin 0.1):

  Shelley  a reward account that leads pool 1 and delegates to pool 2 (pre-Allegra aggregation keeps
           only the leader reward of the two), outputs at minUTxOValue and one lovelace below, MIR
           from the reserves submitted early, mid-epoch (replaced by a later one in the same epoch),
           just before and just after the 3k/f cut-off, and to a credential that is not registered
  Allegra  stake keys deregistered before and after the reward calculation starts (4k/f): the
           pre-Babbage reward prefilter sends the first one's reward back to the reserves and the
           second one's to the treasury
  Mary     the scaled minimum for native assets at the boundary, values of exactly 4000 and 4001
           bytes, MIR pot transfers and negative amounts (refused before Alonzo)
  Alonzo   a MIR pot transfer and a cancelling negative entry (allowed from protocol 5), values of
           exactly 5000 and 5001 bytes against maxValueSize

Transactions are built and signed by `eratx` because cardano-cli can only build Conway
transactions. A rejected submission is logged and never fatal; a submission that must be rejected
(`expect_reject`) counts as `unexpected` when the reference accepts it or names another reason. Only the
reference's verdict is checked here (the Scala rules replay the refused bytes in unit tests): a refusal
counts when it names each expected predicate failure as a whole constructor, and its evidence row says
whether the reference's failure list is exactly those failures (`ListMatch.EXACT`) or holds others too
(`CONTAINS`); the summary's `refusalMatches` counts both.
"""
import enum
import json
import random
import re
import subprocess
import threading
import time

from . import eratx, util
from .eratx import Key

FEE = 250_000
MIN_UTXO_ASSET_NAME = b"minb"
MAGIC = 1082026
ENV = "/work/env"
SOCKET = f"{ENV}/socket/node1/sock"
ERAS = ["shelley", "allegra", "mary", "alonzo", "babbage", "conway"]


def mary_min_utxo(min_utxo_value, assets):
    """Mary `scaledMinDeposit`: the parameter for an ada-only value, else
    max(mv, (mv // 27) * (27 + size)) with size in 8-byte words (cardano-ledger Mary.TxOut / Mary.Value)."""
    if not assets:
        return min_utxo_value
    triples = sum(len(names) for names in assets.values())
    distinct = {n for names in assets.values() for n in names}
    packed = triples * 12 + sum(len(n) for n in distinct) + 28 * len(assets)
    words = 6 + (packed + 7) // 8
    return max(min_utxo_value, (min_utxo_value // 27) * (27 + words))


def value_bytes(coin, assets):
    """Length of the CBOR value `[coin, {policy: {name: quantity}}]` (the ledger's `serialize v`)."""
    def head(n):
        return 1 if n < 24 else 2 if n < 256 else 3 if n < 65536 else 5 if n < 2 ** 32 else 9
    if not assets:
        return head(coin)
    inner = sum(2 + 28 + head(len(names)) + sum(head(len(n)) + len(n) + head(q) for n, q in names.items())
                for names in assets.values())
    return 1 + head(coin) + head(len(assets)) + inner


NO_VERDICT_ATTEMPTS = 3  # submissions of a transaction the reference must refuse, when none returns a verdict
NO_VERDICT_PAUSE = 3  # seconds between them


def _spans(text, start=0):
    """Yield (index, character, depth) of `text` outside Haskell string literals, with the bracket depth
    before the character; brackets are (), [] and {}."""
    depth, quoted, escaped = 0, False, False
    for i in range(start, len(text)):
        c = text[i]
        if quoted:
            if escaped:
                escaped = False
            elif c == "\\":
                escaped = True
            elif c == '"':
                quoted = False
            continue
        if c == '"':
            quoted = True
            continue
        if c in ")]}":
            depth -= 1
        yield i, c, depth
        if c in "([{":
            depth += 1


def _split_top(text, separator):
    """`text` split at every `separator` outside brackets and strings."""
    parts, last = [], 0
    for i, _, depth in _spans(text):
        if depth == 0 and i >= last and text.startswith(separator, i):
            parts.append(text[last:i])
            last = i + len(separator)
    return parts + [text[last:]]


def failure_list(text):
    """The predicate failures of the `ApplyTxError (f :| [g,h])` that `cardano-cli transaction submit` prints
    (the `Show` of the ledger's `NonEmpty` failure list), as their Haskell texts in order. Pure. None when the
    text holds no complete such list."""
    at = text.find("ApplyTxError (")
    if at < 0:
        return None
    start = at + len("ApplyTxError (")
    # the parenthesis that closes the one after `ApplyTxError` takes the depth below zero
    end = next((i for i, c, depth in _spans(text, start) if c == ")" and depth < 0), None)
    if end is None:
        return None
    parts = _split_top(text[start:end], " :| ")
    if len(parts) != 2 or not parts[1].startswith("[") or not parts[1].endswith("]"):
        return None
    rest = parts[1][1:-1]
    return [parts[0].strip()] + ([p.strip() for p in _split_top(rest, ",")] if rest.strip() else [])


def names_constructor(text, name):
    """`name` occurs in `text` as a whole Haskell identifier (not as part of a longer constructor)."""
    return re.search(rf"(?<![A-Za-z0-9_']){re.escape(name)}(?![A-Za-z0-9_'])", text) is not None


class ListMatch(enum.Enum):
    """How a refusal that names every expected predicate failure matches the expectation.

    EXACT: the reference's list has one failure per expected name, each naming its own, in order.
    CONTAINS: the expected names occur, but the list is longer (the transaction broke other rules too) or
    a name occurs only inside another failure; recorded, not counted as an exact match.
    UNPARSED: the failure list could not be read from the text."""
    EXACT = "exact"
    CONTAINS = "contains"
    UNPARSED = "unparsed"


def list_match(text, expected):
    """(ListMatch, number of failures or None) of a refusal text against the expected names."""
    listed = failure_list(text)
    if listed is None:
        return ListMatch.UNPARSED, None
    exact = len(listed) == len(expected) and all(names_constructor(f, e) for f, e in zip(listed, expected))
    return (ListMatch.EXACT if exact else ListMatch.CONTAINS), len(listed)


class Verdict:
    """What the reference said about a submission that must be refused: `kind` is `refused` (a ledger
    refusal naming every expected predicate failure as a whole constructor), `wrongReason` (a ledger refusal
    that does not), `accepted` (the reference took the transaction: a divergence from the rule under test) or
    `noVerdict` (the CLI or the container failed before the ledger spoke: nothing is known about the rule).
    A refusal also carries `match` and `failures` (`list_match`): whether the reference's list is exactly the
    expected failures or holds others too."""

    def __init__(self, kind, text, match=None, failures=None):
        self.kind, self.text, self.match, self.failures = kind, text, match, failures

    def __repr__(self):
        return f"Verdict({self.kind!r}, {self.text[:80]!r}, {self.match})"


def submission_verdict(returncode, stdout, stderr, expected):
    """Classify the result of `cardano-cli transaction submit` for a transaction that must be refused with the
    predicate failure names `expected`. Pure. A ledger refusal is recognised by the `TxValidationError`
    the CLI prints for it (or by an expected name); a non-zero exit without one is no verdict."""
    text = (stderr or stdout).strip().split("CallStack")[0].strip()
    if returncode == 0:
        return Verdict("accepted", text or "(the CLI printed nothing)")
    if all(names_constructor(text, e) for e in expected):
        return Verdict("refused", text, *list_match(text, expected))
    if "TxValidationError" in text:
        return Verdict("wrongReason", text)
    return Verdict("noVerdict", text or "(the CLI printed nothing)")


def match_counts(matches):
    """{match kind: count} over the `ListMatch` of each expected refusal (None for one that was not a refusal):
    the headline, exact versus contains."""
    return {m.value: sum(1 for x in matches if x is m) for m in ListMatch}


class Wallet:
    def __init__(self, rng):
        self.pay = Key(json.dumps({"cborHex": "5820" + rng.randbytes(32).hex()}))
        self.stake = Key(json.dumps({"cborHex": "5820" + rng.randbytes(32).hex()}))
        self.address = eratx.base_address(self.pay.hash, self.stake.hash)
        self.reward = eratx.reward_address(self.stake.hash)
        self.registered = False


class EraLoad:
    """One instance per cluster; `run` is the worker-thread body, `stats` its running totals."""

    def __init__(self, name, lsq_factory, log=print, seed=7):
        self.name, self.lsq_factory, self.log = name, lsq_factory, log
        self.rng = random.Random(seed)
        self.wallets = [Wallet(self.rng) for _ in range(6)]
        self.stats = {}
        self.done = set()
        self.tip = None
        self.keys = {}
        self.script_address = None
        self.ready = threading.Event()  # set by the driver once the pools exist
        self.stop = threading.Event()
        self.pending = 0
        self.ext = None  # govextra.Extended while the extended governance scenarios run
        self.dynamic_forks = False  # the protocol walk (protocolwalk.py) proposes every hard fork, the base rounds none
        self.ext_thread = None
        self.evidence = []  # expected rejections and what the reference said
        self.era_first = {}
        self.plutus_evidence = []
        self.auxiliary_evidence = []
        self.refusal_evidence = []
        self.last_verdict = None  # `Verdict` of the last `expect_rejected`
        self.must_reject_matches = []  # `ListMatch` (or None) of each `expect_reject` submission
        self.negative_matches = []  # `ListMatch` (or None) of each `expect_rejected` verdict

    # ------------------------------------------------------------ reference plumbing
    def sh(self, script, stdin=None, timeout=60):
        return subprocess.run(["docker", "exec", "-i", f"{self.name}-ref", "/bin/sh", "-c", script],
                              input=stdin, text=True, capture_output=True, timeout=timeout)

    def read_key(self, path):
        if path not in self.keys:
            self.keys[path] = Key(self.sh(f"cat {path}").stdout)
        return self.keys[path]

    def snapshot(self):
        """(tip slot, era index, {address: [(txin, lovelace, assets)]}, raw utxo)"""
        with self.lsq_factory() as s:
            s.acquire()
            era = self.current_era(s)
            slot, _ = s.ledger_query(era, [0])
            utxo = s.ledger_query(era, [7])
        by_addr = {}
        for (txid, ix), out in utxo.items():
            address, value = (out[0], out[1]) if isinstance(out, list) else (out[0], out[1])  # array or map form
            coin, assets = (value, {}) if isinstance(value, int) else (value[0], value[1])
            by_addr.setdefault(address, []).append(((txid.hex(), ix), coin, assets))
        return slot, era, by_addr

    def current_era(self, session):
        # the era the node is in: ask with each index from the newest down (a wrong index answers a mismatch)
        for index in range(6, 0, -1):
            r = session.query_raw([0, [0, [index, [1]]]])  # GetEpochNo
            if isinstance(r, list) and len(r) == 1:
                return index
        raise RuntimeError("no era answered")

    def epoch_info(self):
        r = self.sh(f"cardano-cli query tip --testnet-magic {MAGIC} --socket-path {SOCKET}")
        return json.loads(r.stdout)

    def submit(self, era, tx, label, expect=None):
        env = eratx.envelope(era, tx)
        r = self.sh(f"mkdir -p /work/tx && cat > /work/tx/{label}.tx && cardano-cli conway transaction submit --testnet-magic {MAGIC} "
                    f"--socket-path {SOCKET} --tx-file /work/tx/{label}.tx", stdin=env)
        ok = r.returncode == 0
        # the reference's reason is in the first lines; the Haskell call stack that follows is noise
        text = (r.stderr or r.stdout).strip().split("CallStack")[0].strip()
        if expect is not None:
            # the refusal must name the rule under test (a whole constructor) and must not be a side effect of
            # a stale input or an unbalanced transaction, which would also refuse it
            named = names_constructor(text, expect)
            stale = (names_constructor(text, "BadInputsUTxO") or names_constructor(text, "ValueNotConservedUTxO")) \
                and not named
            expected = not ok and named and not stale
            verdict = "accepted" if ok else "expected" if expected else "other reason"
            match, count = list_match(text, [expect]) if expected else (None, None)
            self.must_reject_matches.append(match)
            self.evidence.append({"era": era, "label": label, "mustReject": expect, "verdict": verdict,
                                  "match": match.value if match else None, "failures": count,
                                  "error": "" if ok else text[:400]})
            if not ok:
                self.keep_raw_reason(era, tx, label, expect)
            key = "expectedRejected" if verdict == "expected" else "unexpected"
            self.stats[key] = self.stats.get(key, 0) + 1
            self.log({key: label, "mustReject": expect, "verdict": verdict, "match": match.value if match else None,
                      "failures": count, "error": text[:400]})
            return False
        self.stats[label.split("-")[0]] = self.stats.get(label.split("-")[0], 0) + (1 if ok else 0)
        if not ok:
            self.stats["rejected"] = self.stats.get("rejected", 0) + 1
            self.log({"rejected": label, "error": text[:900]})
        return ok

    ERA_INDEX = {"shelley": 1, "allegra": 2, "mary": 3, "alonzo": 4, "babbage": 5, "conway": 6}

    def keep_raw_reason(self, era, tx, label, expect):
        """The reference's raw `ApplyTxErr` for a refused transaction of an earlier era, over
        LocalTxSubmission, kept with the transaction in `refused-raw.jsonl` of the run directory:
        the bytes the Scala encodings of those eras (`PreConwayFailureCbor`) are checked against."""
        try:
            with self.lsq_factory() as s:
                reason = s.submit(tx, self.ERA_INDEX[era])
        except Exception as e:  # evidence only: a failed capture must not stop the cluster walk
            self.log({"rawReasonFailed": label, "error": repr(e)})
            return
        if reason is not None:
            self.keep_tx("refused-raw.jsonl", {"label": label, "era": era, "mustReject": expect,
                                               "tx": tx.hex(), "reason": reason.hex()})

    def confirm(self, txid, timeout=25):
        """Wait until an output of `txid` shows up in the UTxO."""
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline and not self.stop.is_set():
            _, _, by_addr = self.snapshot()
            if any(i[0][0] == txid for outs in by_addr.values() for i in outs):
                return True
            time.sleep(0.4)
        return False

    # ------------------------------------------------------------ transaction helpers
    def funds(self, address):
        _, _, by_addr = self.snapshot()
        return sorted(by_addr.get(address, []), key=lambda i: -i[1])

    def act(self, era, label, wallet, outputs=(), certs=None, withdrawals=None, deposit=0, refund=0, mint=None,
            extra_signers=(), native_scripts=(), valid_from=None, ttl=None, spend_extra=(), metadata=None,
            aux_scripts=(), collateral=None, required_signers=None, reference_inputs=None, network_id=None,
            collateral_return=None, total_collateral=None, avoid=(), fee=FEE, expect_reject=None, record=None, refusal=None,
            **build):
        """Spend from `wallet` (largest ADA-only output plus any `spend_extra` inputs), pay `outputs`
        ((address, coin, assets, kwargs) tuples), send the change back to the wallet, sign, submit and
        wait for the confirmation. Returns the transaction id, or None."""
        utxos = [u for u in self.funds(wallet.address) if not u[2] and u[0] not in avoid]
        if not utxos:
            self.log({"skipped": label, "reason": "wallet has no ADA-only output"})
            return None
        needed = fee + deposit + 3_000_000 + sum(o[1] for o in outputs) - sum((withdrawals or {}).values()) - refund
        chosen, total = [], 0
        for u in utxos:  # largest first, until the outputs, fee, deposits and a change output are covered
            if total >= needed and chosen:
                break
            chosen.append(u)
            total += u[1]
        chosen += list(spend_extra)
        slot = self.tip["slot"] if self.tip else 0
        if ttl is None and era == "shelley":
            ttl = slot + 3000
        # change = inputs + withdrawals + refunds + (assets minted) - outputs - fee - deposits
        coin_in = sum(u[1] for u in chosen) + sum((withdrawals or {}).values()) + refund
        assets = {}
        for u in chosen:
            for p, names in u[2].items():
                for n, q in names.items():
                    assets.setdefault(p, {})[n] = assets.setdefault(p, {}).get(n, 0) + q
        for p, names in (mint or {}).items():
            for n, q in names.items():
                assets.setdefault(p, {})[n] = assets.setdefault(p, {}).get(n, 0) + q
        coin_out = fee + deposit
        tx_outputs = []
        for address, coin, out_assets, kw in outputs:
            coin_out += coin
            for p, names in (out_assets or {}).items():
                for n, q in names.items():
                    assets.setdefault(p, {})[n] = assets.setdefault(p, {}).get(n, 0) - q
            tx_outputs.append(eratx.tx_out(era, address, coin, out_assets, **kw))
        assets = {p: {n: q for n, q in names.items() if q} for p, names in assets.items()}
        assets = {p: names for p, names in assets.items() if names}
        if any(q < 0 for names in assets.values() for q in names.values()):
            self.log({"skipped": label, "reason": "not enough assets"})
            return None
        change = coin_in - coin_out
        if change < 2_000_000:
            self.log({"skipped": label, "reason": f"change {change} too small"})
            return None
        tx_outputs.append(eratx.tx_out(era, wallet.address, change, assets or None))
        signers = [wallet.pay, *extra_signers]
        wits = list(native_scripts)
        sorted_inputs = sorted((bytes.fromhex(t), i) for t, i in (u[0] for u in chosen))
        if callable(build.get("redeemers")):
            build["redeemers"] = build["redeemers"]([(t.hex(), i) for t, i in sorted_inputs])
        if callable(build.get("script_data")):
            build["script_data"] = build["script_data"](build.get("redeemers"), build.get("datums"))
        try:
            tx, txid = eratx.build_tx(
                era, [u[0] for u in chosen], tx_outputs, fee, signers, ttl=ttl, valid_from=valid_from, certs=certs,
                withdrawals=withdrawals, mint=mint, native_scripts=wits, collateral=collateral,
                required_signers=required_signers, reference_inputs=reference_inputs, network_id=network_id,
                metadata=metadata, aux_scripts=aux_scripts, collateral_return=collateral_return,
                total_collateral=total_collateral, **build)
        except Exception as e:  # a bug in a scenario must not stop the cluster walk
            self.log({"buildFailed": label, "error": repr(e)})
            return None
        if refusal is not None:
            # the reference's raw refusal is kept for the failure encodings; nothing is expected of its text
            from .erarefusal import record as record_refusal
            record_refusal(self, era, tx, label)
            return None
        if expect_reject is not None and not isinstance(expect_reject, str):
            # governance scenarios: a list of predicate-failure names, the refused bytes are kept for replay
            return self.expect_rejected(era, tx, f"{label}-{txid[:8]}", expect_reject, record)
        if not self.submit(era, tx, f"{label}-{txid[:8]}", expect=expect_reject):
            return None
        if record:
            self.keep_tx(record, {"label": label, "era": era, "outcome": "accepted", "tx": tx.hex()})
        return txid if self.confirm(txid) else None

    def keep_tx(self, file, entry):
        """Append one JSON line to `file` in the run directory (transactions kept for replay in unit tests)."""
        with (util.data_root(self.name) / file).open("a") as f:
            f.write(json.dumps(entry) + "\n")

    def expect_rejected(self, era, tx, label, expected, record=None, attempts=NO_VERDICT_ATTEMPTS):
        """Submit `tx` and require the reference to refuse it with every text of `expected` in its
        error (the predicate failure names). A matching refusal counts as `negativeOk`; the
        transaction is appended to `negative-txs.jsonl` of the run directory, so the Scala rules can
        be replayed on the very bytes the reference refused. Returns True when it was refused as expected.

        The verdict is one of `submission_verdict`'s: a submission that ends without any ledger verdict
        (the CLI or `docker exec` failed, empty output) is submitted again, up to `attempts` times, and logged
        as `negativeNoVerdict` (not as a refusal that went wrong); only the last verdict is counted. An
        acceptance is final and is kept in `last_verdict` for the scenario to treat as a divergence."""
        env = eratx.envelope(era, tx)
        for attempt in range(1, attempts + 1):
            r = self.sh(f"mkdir -p /work/tx && cat > /work/tx/{label}.tx && cardano-cli conway transaction submit "
                        f"--testnet-magic {MAGIC} --socket-path {SOCKET} --tx-file /work/tx/{label}.tx", stdin=env)
            verdict = submission_verdict(r.returncode, r.stdout, r.stderr, expected)
            if verdict.kind != "noVerdict" or attempt == attempts:
                break
            self.log({"negativeNoVerdict": label, "attempt": attempt, "returncode": r.returncode,
                      "stdout": r.stdout[:300], "stderr": r.stderr[:300]})
            time.sleep(NO_VERDICT_PAUSE)
        self.last_verdict = verdict
        ok = verdict.kind == "refused"
        self.stats["negativeOk" if ok else "negativeUnexpected"] = self.stats.get(
            "negativeOk" if ok else "negativeUnexpected", 0) + 1
        match = verdict.match.value if verdict.match else None
        self.negative_matches.append(verdict.match)
        self.log({"negative": label, "refusedAsExpected": ok, "verdict": verdict.kind, "returncode": r.returncode,
                  "expected": list(expected), "match": match, "failures": verdict.failures,
                  "error": verdict.text[:700]})
        entry = {"label": label, "era": era, "outcome": "refused", "expected": list(expected), "match": match,
                 "failures": verdict.failures, "error": verdict.text[:2000], "tx": tx.hex()}
        if ok:
            self.keep_tx("negative-txs.jsonl", entry)
        if record:
            self.keep_tx(record, entry if ok else {**entry, "outcome": "unexpected", "verdict": verdict.kind})
        return ok

    # ------------------------------------------------------------ scenarios
    def once(self, key):
        if key in self.done:
            return False
        self.done.add(key)
        return True

    def asset_utxos(self, wallet):
        return [u for u in self.funds(wallet.address) if u[2]]

    def transfers(self, era, count=2):
        for _ in range(count):
            _, _, by_addr = self.snapshot()
            balance = {i: sum(u[1] for u in by_addr.get(x.address, []) if not u[2]) for i, x in enumerate(self.wallets)}
            richest = max(balance, key=balance.get)
            a = self.wallets[richest]
            b = self.rng.choice([x for x in self.wallets if x is not a])
            outs = [(b.address, self.rng.randint(3, 12) * 1_000_000, None, {}) for _ in range(self.rng.randint(1, 3))]
            self.act(era, "transfer", a, outs)

    def reward_balances(self):
        """{stake credential hash: reward balance} of the registered accounts, from the reference ledger."""
        with self.lsq_factory() as s:
            s.acquire()
            index = self.current_era(s)
            nes = s.ledger_query(index, [12])
        return {c[1]: a[1] for c, a in nes[3][1][0][1][0][0].items()}

    def withdrawals(self, era):
        """Withdraw the whole reward balance of any registered, rewarded wallet."""
        balances = self.reward_balances()
        for w in self.wallets:
            amount = balances.get(w.stake.hash, 0)
            if w.registered and amount > 0:
                self.act(era, "withdrawal", w, [], withdrawals={w.reward: amount}, extra_signers=[w.stake])

    def deregister(self, era, label, wallet):
        """Withdraw the reward balance (a deregistration needs it to be zero) and deregister, in one transaction."""
        amount = self.reward_balances().get(wallet.stake.hash, 0)
        txid = self.act(era, label, wallet, [], certs=[eratx.stake_key_cert(1, wallet.stake.hash)], refund=400_000,
                        withdrawals={wallet.reward: amount} if amount else None, extra_signers=[wallet.stake])
        if txid:
            wallet.registered = False
            self.log({"deregistered": label, "slot": self.epoch_info().get("slotInEpoch"), "reward": amount})
        return txid

    def min_utxo_value(self):
        """The Shelley..Mary `minUTxOValue` parameter (absent from Alonzo on)."""
        pp = json.loads(self.sh(f"cardano-cli query protocol-parameters --testnet-magic {MAGIC} --socket-path {SOCKET}").stdout)
        return pp.get("minUTxOValue", 0)

    def min_utxo_boundary(self, era):
        """An ada-only output of exactly minUTxOValue is accepted, one lovelace less is refused."""
        mv = self.min_utxo_value()
        if not mv:
            return
        w, slot = self.wallets, self.tip["slot"]
        self.act(era, "min-ok", w[4], [(w[5].address, mv, None, {})], ttl=slot + 2000)
        self.act(era, "min-low", w[4], [(w[5].address, mv - 1, None, {})], ttl=slot + 2000,
                 expect_reject="OutputTooSmallUTxO")

    def mir_cert(self, era, label, pot, body, expect=None, certs=None):
        """A MIR certificate (`body` is a credential-hash/amount pair or a pot-to-pot coin), signed by the genesis delegates."""
        cold = [self.read_key(f"{ENV}/pools-keys/pool{n}/cold.skey") for n in (1, 2, 3)]
        if certs is None:
            if isinstance(body, tuple):
                body = {eratx.Raw(eratx.enc([0, body[0]])): body[1]}
            certs = [[6, [pot, body]]]
        return self.act(era, label, self.wallets[5], [], certs=certs, extra_signers=cold, expect_reject=expect)

    def sized_assets(self, policy, target, coin):
        """Assets under one policy whose value `[coin, ...]` serialises to exactly `target` bytes."""
        n0 = (target - value_bytes(coin, {policy: {b"": 1}}) - 2) // 35
        for n in (n0, n0 - 1):
            base = {f"{i:032d}".encode(): 1 for i in range(n)}
            for length in range(33):
                for q in (1, 24, 256):
                    names = dict(base)
                    names[b"e" * length] = q
                    if value_bytes(coin, {policy: names}) == target:
                        return {policy: names}
        raise RuntimeError(f"no asset set serialises to {target} bytes")

    def value_size_case(self, era, label, size, coin, expect=None):
        """Mint and pay a value of exactly `size` serialised bytes to wallet 2; the wallet with the most ada pays."""
        w = self.wallets
        script = eratx.sig_script(w[5].pay.hash)
        assets = self.sized_assets(eratx.script_hash(script), size, coin)
        _, _, by_addr = self.snapshot()
        payer = max(w, key=lambda x: max([u[1] for u in by_addr.get(x.address, []) if not u[2]] or [0]))
        self.act(era, label, payer, [(w[2].address, coin, assets, {})], mint=assets, native_scripts=[script],
                 extra_signers=[w[5].pay], ttl=self.tip["slot"] + 2000, expect_reject=expect)

    def shelley_features(self, era):
        w = self.wallets
        if self.once("fund"):
            funder = self.delegator(2)
            outs = [(x.address, 450_000_000, None, {}) for x in w]
            self.act(era, "fund", funder, outs)
        if self.once("register"):
            certs = [eratx.stake_key_cert(0, x.stake.hash) for x in w[:4]]
            for x in w[:4]:
                x.registered = True
            self.act(era, "register", w[4], [], certs=certs, deposit=4 * 400_000, extra_signers=[x.stake for x in w[:4]])
        if self.once("delegate"):
            pools = [self.read_key(f"{ENV}/pools-keys/pool{n}/cold.skey").hash for n in (1, 2, 3)]
            certs = [eratx.delegation_cert(w[i].stake.hash, pools[i % 3]) for i in range(3)]
            self.act(era, "delegate", w[4], [], certs=certs, extra_signers=[x.stake for x in w[:3]])
        if self.once("min-utxo"):
            self.min_utxo_boundary(era)
        if self.once("newpool"):
            self.new_pool(era)
        if self.once("repool"):
            self.re_register_pool(era, 1)
        if self.once("mir"):
            self.mir(era, 0, w[0], 7_000_000)
        if self.once("gendeleg"):
            self.genesis_delegation(era)

    def delegator(self, n):
        """A wallet-like view of stake delegator `n` of the cluster environment."""
        funder = Wallet(random.Random(0))
        funder.pay = self.read_key(f"{ENV}/stake-delegators/delegator{n}/payment.skey")
        funder.stake = self.read_key(f"{ENV}/stake-delegators/delegator{n}/staking.skey")
        funder.address = eratx.base_address(funder.pay.hash, funder.stake.hash)
        return funder

    def new_pool(self, era):
        w = self.wallets[3]
        self.extra_pool = {"operator": w.pay.hash, "vrf": eratx.blake2b(b"extra-pool-vrf", 32), "pledge": 0,
                           "cost": 500_000, "margin": [1, 10], "reward_account": w.reward, "owners": [w.stake.hash]}
        cert = eratx.pool_registration_cert(self.extra_pool)
        self.act(era, "newpool", self.wallets[5], [], certs=[cert], deposit=self.pool_deposit(),
                 extra_signers=[w.pay, w.stake])

    def pool_deposit(self):
        pp = json.loads(self.sh(f"cardano-cli query protocol-parameters --testnet-magic {MAGIC} --socket-path {SOCKET}").stdout)
        return pp["stakePoolDeposit"]

    def re_register_pool(self, era, n):
        """Change a running pool's cost and margin: effective next epoch (future pool parameters)."""
        cold = self.read_key(f"{ENV}/pools-keys/pool{n}/cold.skey")
        owner = self.read_key(f"{ENV}/stake-delegators/delegator{n}/staking.skey")
        with self.lsq_factory() as s:
            s.acquire()
            index = self.current_era(s)
            nes = s.ledger_query(index, [12])
        p = nes[3][1][0][0][1][cold.hash]
        params = {"operator": cold.hash, "vrf": p[0], "pledge": p[1], "cost": 1_000_000 + n, "margin": [3, 100],
                  "reward_account": eratx.reward_address(p[4][1]), "owners": [o for o in p[5]]}
        self.act(era, "repool", self.wallets[5], [], certs=[eratx.pool_registration_cert(params)],
                 extra_signers=[cold, owner])

    def mir(self, era, pot, target, amount):
        """Move instantaneous rewards from the reserves (pot 0) or treasury (1), signed by the genesis delegates."""
        cold = [self.read_key(f"{ENV}/pools-keys/pool{n}/cold.skey") for n in (1, 2, 3)]
        credential = eratx.Raw(eratx.enc([0, target.stake.hash]))
        cert = [6, [pot, {credential: amount}]]
        self.act(era, f"mir{pot}", self.wallets[5], [], certs=[cert], extra_signers=cold)

    def genesis_delegation(self, era):
        gen = self.read_key(f"{ENV}/genesis-keys/genesis1/key.skey")
        cold = self.read_key(f"{ENV}/pools-keys/pool1/cold.skey")
        vrf_hash = self.sh(f"cardano-cli node key-hash-VRF --verification-key-file {ENV}/pools-keys/pool1/vrf.vkey").stdout.strip()
        cert = [5, gen.hash, cold.hash, bytes.fromhex(vrf_hash)]
        self.act(era, "gendeleg", self.wallets[5], [], certs=[cert], extra_signers=[gen])

    def allegra_features(self, era):
        w = self.wallets
        slot = self.tip["slot"]
        if self.once("dereg-before-cutoff"):
            # before the reward calculation starts (slot 4k/f of the epoch): the account is not in the
            # prefilter's set, so its reward stays in the reserves
            self.deregister(era, "dereg-early", w[1])
        if self.once("min-utxo-allegra"):
            self.min_utxo_boundary(era)
        if self.once("interval"):
            self.act(era, "interval", w[4], [(w[5].address, 5_000_000, None, {})], valid_from=max(slot - 20, 0), ttl=slot + 2000)
        if self.once("metadata"):
            self.act(era, "metadata", w[4], [(w[5].address, 4_000_000, None, {})], metadata={674: {"msg": ["e2e", "metadata"]}},
                     ttl=slot + 2000)
        if self.once("timelock-fund"):
            self.lock_slot = slot + 60
            self.script = eratx.all_script(eratx.sig_script(w[4].pay.hash), eratx.after_script(self.lock_slot))
            self.script_address = eratx.enterprise_address(eratx.script_hash(self.script), script=True)
            self.act(era, "timelock-fund", w[4], [(self.script_address, 20_000_000, None, {})], ttl=slot + 2000)
        if self.script_address and self.tip["slot"] > self.lock_slot + 5 and self.once("timelock-spend"):
            self.spend_script(era)
        if self.once("retire-pool"):
            self.retire_extra_pool(era)

    def spend_script(self, era):
        _, _, by_addr = self.snapshot()
        locked = by_addr.get(self.script_address, [])
        if not locked:
            return
        funder = self.wallets[4]
        self.act(era, "timelock-spend", funder, [(self.wallets[5].address, 10_000_000, None, {})], spend_extra=locked,
                 native_scripts=[self.script], valid_from=self.lock_slot + 1, ttl=self.tip["slot"] + 2000)

    def retire_extra_pool(self, era):
        w = self.wallets[3]
        epoch = self.tip["epoch"]
        self.act(era, "retire", self.wallets[5], [], certs=[eratx.pool_retirement_cert(w.pay.hash, epoch + 2)],
                 extra_signers=[w.pay])

    def mary_features(self, era):
        w = self.wallets
        slot = self.tip["slot"]
        policy_script = eratx.sig_script(w[5].pay.hash)
        policy = eratx.script_hash(policy_script)
        if self.once("mint"):
            mint = {policy: {b"coinA": 1000, b"coinB": 500}}
            outs = [(w[4].address, 3_000_000, {policy: {b"coinA": 300}}, {}),
                    (w[3].address, 3_000_000, {policy: {b"coinB": 100, b"coinA": 50}}, {})]
            self.act(era, "mint", w[5], outs, mint=mint, native_scripts=[policy_script], ttl=slot + 2000)
        if self.once("assets-transfer"):
            outs = [(w[0].address, 3_000_000, {policy: {b"coinA": 20}}, {})]
            self.act(era, "assets-transfer", w[4], outs, spend_extra=self.asset_utxos(w[4])[:1])
        if self.once("burn"):
            self.act(era, "burn", w[5], [], mint={policy: {b"coinA": -100}}, native_scripts=[policy_script],
                     spend_extra=self.asset_utxos(w[5])[:1], ttl=slot + 2000)
        if self.once("mir-treasury"):
            self.mir(era, 1, w[1], 3_000_000)
        if self.once("min-utxo-mary"):
            self.min_utxo_boundary(era)  # ada-only outputs: exactly the parameter
        mv = self.min_utxo_value()
        if mv and self.once("min-assets"):
            # scaledMinDeposit for one asset is 1444443 with the 1 ada parameter, but the Allegra/Mary rule
            # (`pointwise (<) v (inject min)`) never calls an output with assets too small: the reference
            # accepts one lovelace less, and even one lovelace
            name = MIN_UTXO_ASSET_NAME
            need = mary_min_utxo(mv, {policy: {name: 1}})
            self.log({"scaledMinDeposit": need})
            for tag, coin, asset in (("ok", need, name), ("below", need - 1, name + b"2"), ("one", 1, name + b"3")):
                self.act(era, f"min-assets-{tag}", w[5], [(w[4].address, coin, {policy: {asset: 1}}, {})],
                         mint={policy: {asset: 1}}, native_scripts=[policy_script], ttl=slot + 2000)
        if self.once("value-4000"):
            # Allegra and Mary: serialised values larger than 4000 bytes are refused
            self.value_size_case(era, "value-4000", 4000, 30_000_000)
            self.value_size_case(era, "value-4001", 4001, 30_000_000, expect="OutputTooBigUTxO")
        if self.once("mir-transfer-refused"):
            self.mir_cert(era, "mir-transfer", 0, 1_000_000, expect="MIRTransferNotCurrentlyAllowed")
        if self.once("mir-negative-refused"):
            self.mir_cert(era, "mir-negative", 0, (w[1].stake.hash, -1_000_000),
                          expect="MIRNegativesNotCurrentlyAllowed")
        if self.once("timelock-mint"):
            # a policy that only mints before an expiry slot
            expiring = eratx.all_script(eratx.sig_script(w[4].pay.hash), eratx.before_script(slot + 3000))
            p2 = eratx.script_hash(expiring)
            self.act(era, "timelock-mint", w[4], [(w[1].address, 3_000_000, {p2: {b"limited": 10}}, {})],
                     mint={p2: {b"limited": 10}}, native_scripts=[expiring], ttl=slot + 2000)
        if self.once("dereg"):
            self.act(era, "dereg", w[5], [], certs=[eratx.stake_key_cert(1, w[3].stake.hash)], refund=400_000,
                     extra_signers=[w[3].stake])
            w[3].registered = False

    def alonzo_features(self, era):
        w = self.wallets
        slot = self.tip["slot"]
        if self.once("mir-transfer"):
            # allowed from protocol 5 (hardforkAlonzoAllowMIRTransfer): the pots move at the boundary
            self.mir_cert(era, "mir-transfer", 0, 2_000_000)
        if self.once("mir-cancel"):
            # entries add up from protocol 5: +3 ada and -1 ada leave 2 ada, where before they replaced each other
            target = eratx.Raw(eratx.enc([0, w[2].stake.hash]))
            self.mir_cert(era, "mir-cancel", 0, None,
                          certs=[[6, [0, {target: 3_000_000}]], [6, [0, {target: -1_000_000}]]])
        if self.once("value-5000"):
            # maxValueSize (5000 in the devnet genesis): exactly that is accepted, one byte more is refused
            self.value_size_case(era, "value-5000", 5000, 40_000_000)
            self.value_size_case(era, "value-5001", 5001, 40_000_000, expect="OutputTooBigUTxO")
        if self.once("collateral"):
            utxos = [u for u in self.funds(w[1].address) if not u[2]]
            if len(utxos) >= 2:
                # one input to spend, another named as collateral (checked only when scripts run)
                coll = utxos[1][0]
                self.act(era, "collateral", w[1], [(w[2].address, 6_000_000, None, {})], collateral=[coll],
                         avoid=[coll], ttl=slot + 2000)
        if self.once("datum-hash"):
            datum_hash = eratx.blake2b(eratx.enc([1, 2, 3]))
            self.act(era, "datum-hash", w[4], [(w[2].address, 5_000_000, None, {"datum_hash": datum_hash})],
                     required_signers=[w[4].pay.hash], network_id=0, ttl=slot + 2000)

    def babbage_features(self, era):
        w = self.wallets
        slot = self.tip["slot"]
        if self.once("inline-datum"):
            self.act(era, "inline-datum", w[4], [(w[2].address, 5_000_000, None, {"inline_datum": [42, b"inline"]})],
                     ttl=slot + 2000)
        if self.once("ref-script"):
            script = eratx.all_script(eratx.sig_script(w[0].pay.hash))
            self.act(era, "ref-script", w[4], [(w[2].address, 8_000_000, None, {"script_ref": script})],
                     ttl=slot + 2000)
        if self.once("ref-input"):
            _, _, by_addr = self.snapshot()
            refs = by_addr.get(w[2].address, [])
            if refs:
                self.act(era, "ref-input", w[4], [(w[1].address, 4_000_000, None, {})], reference_inputs=[refs[0][0]],
                         ttl=slot + 2000)
        if self.once("collateral-return"):
            utxos = [u for u in self.funds(w[1].address) if not u[2]]
            if len(utxos) >= 2:
                coll = utxos[1]
                ret = eratx.tx_out(era, w[1].address, coll[1] - 1_500_000)
                self.act(era, "collateral-return", w[1], [(w[3].address, 6_000_000, None, {})], collateral=[coll[0]],
                         avoid=[coll[0]], collateral_return=ret, total_collateral=1_500_000, ttl=slot + 2000)

    # ------------------------------------------------------------ Conway governance and Plutus
    def protocol_params(self):
        return json.loads(self.sh(f"cardano-cli conway query protocol-parameters --testnet-magic {MAGIC} "
                                  f"--socket-path {SOCKET}").stdout)

    def treasury(self):
        return int(self.sh(f"cardano-cli conway query treasury --testnet-magic {MAGIC} "
                           f"--socket-path {SOCKET}").stdout.strip())

    def gov_state(self):
        return json.loads(self.sh(f"cardano-cli conway query gov-state --testnet-magic {MAGIC} "
                                  f"--socket-path {SOCKET}", timeout=120).stdout)

    def v3_models(self, pp):
        return {3: pp["costModels"]["PlutusV3"]}

    def gov_setup(self):
        """Keys the governance scenarios need: fresh wallets, DReps, committee cold and hot keys."""
        if hasattr(self, "gw"):
            return
        r = random.Random(11)
        mk = lambda: Key(json.dumps({"cborHex": "5820" + r.randbytes(32).hex()}))
        self.gw = [Wallet(r) for _ in range(5)]
        self.gdrep = [mk() for _ in range(2)]
        self.cold = [mk() for _ in range(2)]
        self.hot = [mk() for _ in range(2)]
        self.script = eratx.plutus_always_succeeds()
        self.script_hash = eratx.plutus_script_hash(self.script)
        self.script_addr = eratx.enterprise_address(self.script_hash, script=True)
        self.genesis_dreps = [self.read_key(f"{ENV}/drep-keys/drep{n}/drep.skey") for n in (1, 2, 3)]
        self.pool_cold = [self.read_key(f"{ENV}/pools-keys/pool{n}/cold.skey") for n in (1, 2, 3)]
        self.proposed = {}
        self.attempts = {}

    def live_genesis_dreps(self):
        """The genesis DReps that are registered (the era-walking cluster registers none)."""
        out = self.sh(f"cardano-cli conway query drep-state --all-dreps --testnet-magic {MAGIC} "
                      f"--socket-path {SOCKET}", timeout=120).stdout
        registered = {c.get("keyHash") for c, _ in json.loads(out)}
        return [k for k in self.genesis_dreps if k.hash.hex() in registered]

    def live_dreps(self):
        """Every DRep key of the scenarios that is registered now."""
        registered = {c.get("keyHash") for c, _ in json.loads(self.sh(
            f"cardano-cli conway query drep-state --all-dreps --testnet-magic {MAGIC} --socket-path {SOCKET}",
            timeout=120).stdout)}
        return [k for k in self.genesis_dreps + self.gdrep if k.hash.hex() in registered]

    def gov_votes(self, label, wallet, casts, signers):
        """One transaction with the votes `casts` ([(voter, [(gov_action_id, vote)])]) signed by `signers`."""
        return self.act("conway", label, wallet, [], votes=eratx.voting_procedures(casts),
                        extra_signers=list(signers), fee=500_000)

    def propose(self, label, wallet, actions, signers=(), anchors=None):
        """Submit proposals; returns the transaction id (the action ids are (txid, index)). `anchors` gives
        actions that are otherwise identical (a proposal set cannot hold the same proposal twice) their own."""
        pp = self.protocol_params()
        deposit = pp["govActionDeposit"]
        proposals = [eratx.proposal(deposit, wallet.reward, a, (anchors or [None] * len(actions))[i])
                     for i, a in enumerate(actions)]
        txid = self.act("conway", label, wallet, [], proposals=proposals, deposit=deposit * len(actions),
                        extra_signers=list(signers), fee=500_000)
        return txid

    def stage(self, key, run, tries=4):
        """Run a scenario stage until it succeeds (a transaction id or truthy result), at most `tries` times."""
        if key in self.done or self.attempts.get(key, 0) >= tries:
            return None
        self.attempts[key] = self.attempts.get(key, 0) + 1
        result = run()
        if result:
            self.done.add(key)
        self.log({"stage": key, "ok": bool(result), "try": self.attempts[key]})
        return result

    def conway_features(self, epoch):
        """The governance and Plutus scenarios, one pass per call (run once per Conway epoch). A stage that
        cannot run is retried in later passes and eventually given up, never fatal."""
        self.gov_setup()
        if not hasattr(self, "first_conway"):
            self.first_conway = epoch
        n = epoch - self.first_conway
        pp = self.protocol_params()
        pv = pp["protocolVersion"]["major"]
        deposit, drep_deposit = pp["stakeAddressDeposit"], pp["dRepDeposit"]
        gw, d = self.gw, self.gdrep
        pools = [k.hash for k in self.pool_cold]
        act = lambda label, wallet, *a, **kw: self.act("conway", label, wallet, *a, fee=kw.pop("fee", 500_000), **kw)
        self.stage("gfund", lambda: act("gfund", self.delegator(3), [(w.address, 200_000_000, None, {}) for w in gw[:4]]))
        if "gfund" not in self.done:
            return
        # stake credentials with pool and DRep delegation, two DReps
        certs = [eratx.reg_stake_vote_deleg_cert(gw[0].stake.hash, pools[0], eratx.drep_ref("abstain"), deposit),
                 eratx.reg_vote_deleg_cert(gw[1].stake.hash, eratx.drep_ref("noconfidence"), deposit),
                 eratx.reg_cert(gw[2].stake.hash, deposit),
                 eratx.reg_deleg_cert(gw[3].stake.hash, pools[1], deposit),
                 eratx.reg_cert(gw[4].stake.hash, deposit),
                 eratx.drep_reg_cert(d[0].hash, drep_deposit, eratx.anchor("https://drep.invalid/0")),
                 eratx.drep_reg_cert(d[1].hash, drep_deposit)]
        self.stage("greg", lambda: act("greg", gw[0], [], certs=certs, deposit=5 * deposit + 2 * drep_deposit,
                                       extra_signers=[w.stake for w in gw] + d))
        if "greg" not in self.done:
            return
        # Plutus V3: lock funds with an inline datum next to a reference script, then spend and mint
        lock = self.stage("plutus-lock", lambda: act(
            "plutus-lock", gw[1], [(self.script_addr, 30_000_000, None, {"inline_datum": [42, b"v3"]}),
                                   (self.script_addr, 20_000_000, None, {}),  # no datum: allowed for V3
                                   (gw[2].address, 5_000_000, None, {"script_ref_plutus": (3, self.script)})]))
        if lock:
            self.proposed["lock"] = lock
        if "plutus-lock" in self.done:
            self.stage("plutus-spend", lambda: self.plutus_spend(pp))
        if "plutus-spend" in self.done and "lock" in self.proposed:
            self.stage("plutus-ref", lambda: self.plutus_ref_mint(pp))
        if n >= 2:
            self.stage("gdeleg", lambda: act(
                "gdeleg", gw[3], [], extra_signers=[gw[3].stake],
                certs=[eratx.vote_deleg_cert(gw[3].stake.hash, eratx.drep_ref("key", d[0].hash))]))
        if n >= 3:
            self.stage("gdeleg2", lambda: act(
                "gdeleg2", gw[0], [], extra_signers=[gw[0].stake],
                certs=[eratx.stake_vote_deleg_cert(gw[0].stake.hash, pools[1], eratx.drep_ref("key", d[1].hash))]))
        # first round of proposals; the votes follow once the proposals are on chain
        phase = "10" if pv >= 10 else "9"
        if n >= 1 and f"gproposals{phase}" not in self.done:
            actions = [eratx.info_action()]
            if pv < 10:
                actions += ([] if self.dynamic_forks else [eratx.hard_fork_action(10, 0)])
                actions += [eratx.param_change_action({3: pp["maxTxSize"] + 8}),
                            eratx.param_change_action({24: pp["maxCollateralInputs"] + 1})]
            else:
                actions += [eratx.param_change_action({16: pp["minPoolCost"] + 1000}),
                            eratx.treasury_withdrawal_action({gw[2].reward: 7_000_000}),
                            eratx.update_committee_action({c.hash: epoch + 40 for c in self.cold}, threshold=(1, 2)),
                            eratx.constitution_action("https://constitution.invalid/v1", eratx.blake2b(b"v1"))]
            txid = self.stage(f"gproposals{phase}", lambda: self.propose(f"gproposals{phase}", gw[1], actions))
            if txid:
                self.proposed[phase] = [(txid, i) for i in range(len(actions))]
        if phase in self.proposed:
            self.stage(f"gvotes{phase}", lambda: self.cast_votes(pv, self.proposed[phase], phase))
        # committee authorisation once the committee has members (protocol version 10 and later)
        if pv >= 10 and "gcommittee" not in self.done and self.committee_members():
            self.stage("gcommittee", lambda: act(
                "gcommittee", gw[3], [], extra_signers=self.cold,
                certs=[eratx.auth_committee_cert(c.hash, h.hash) for c, h in zip(self.cold, self.hot)]))
        extended = self.ext is not None and self.ext.full
        if pv >= 10 and "gcommittee" in self.done and n >= 3 and (not extended or "xrewards" in self.ext.done):
            self.stage("ground2", lambda: self.second_round(pv))
        if extended:
            # the extended rounds follow the chain: the pool thresholds are lowered for the no-confidence
            # round (which then runs on the default pool votes), and the committee is elected after it is gone
            pc1 = self.ext.epochs.get("pc1")
            if "ground2" in self.done and pv >= 11 and pc1 is not None and epoch > pc1:
                self.stage("ground3", lambda: self.third_round(pv))
            if "ground3" in self.done and not self.committee_members():
                self.stage("ground4", lambda: self.fourth_round(pv, epoch))
        else:
            # the no-confidence round dissolves the committee the hard forks of the protocol walk still need
            walked = self.ext is None or self.ext.walk is None or self.ext.walk.complete
            if "ground2" in self.done and n >= 6 and walked:
                self.stage("ground3", lambda: self.third_round(pv))
            if "ground3" in self.done and n >= 8:
                self.stage("ground4", lambda: self.fourth_round(pv, epoch))
        if pv >= 10 and "ground2" in self.done and n >= 5:
            self.stage("gmore", lambda: self.more_gov(pp, pv, epoch))
        if n >= 2:
            self.stage("gdonate", self.donate, tries=6)

    def committee_members(self):
        try:
            return bool(self.gov_state()["committee"]["members"])
        except Exception:
            return False

    def cast_votes(self, pv, ids, phase=""):
        """DRep, pool and committee votes on the first round of proposals."""
        gw = self.gw
        all_ids = [eratx.gov_id(*i) for i in ids]
        info = all_ids[0]
        casts = []
        genesis = self.live_genesis_dreps()
        if pv < 10:
            # bootstrap: DReps may only vote on info actions; pools vote on the hard fork and on security
            # parameters (all_ids: info, hard fork, security parameter change, other parameter change; without the
            # hard fork, which the protocol walk proposes: info, security parameter change, other parameter change)
            pool_ids = all_ids[1:2] if self.dynamic_forks else all_ids[1:3]
            for k in genesis:
                casts.append((eratx.voter("drep", k.hash), [(info, "yes")]))
            casts.append((eratx.voter("drep", self.gdrep[0].hash), [(info, "no")]))
            for p in self.pool_cold[:2]:
                casts.append((eratx.voter("pool", p.hash), [(i, "yes") for i in pool_ids]))
            casts.append((eratx.voter("pool", self.pool_cold[2].hash),
                          [(info, "abstain")] + [(i, "no") for i in pool_ids]))
        else:
            for k in genesis[:2]:
                casts.append((eratx.voter("drep", k.hash), [(i, "yes") for i in all_ids]))
            for k in genesis[2:]:
                casts.append((eratx.voter("drep", k.hash), [(info, "no")] + [(i, "yes") for i in all_ids[1:3]]))
            # without registered genesis DReps (the era-walking cluster) the new DReps carry the vote
            first = "abstain" if genesis else "yes"
            casts.append((eratx.voter("drep", self.gdrep[0].hash), [(i, first) for i in all_ids]))
            casts.append((eratx.voter("drep", self.gdrep[1].hash),
                          [(i, "no" if genesis else "yes") for i in all_ids[3:]]))
            # pools vote on the info action and the committee update (parameters and withdrawals are not theirs)
            for p in self.pool_cold:
                casts.append((eratx.voter("pool", p.hash), [(info, "yes"), (all_ids[3], "yes")]))
        return self.gov_votes(f"gvotes{phase}", gw[0], casts, genesis + self.gdrep + self.pool_cold)

    def committee_root(self):
        previous = self.gov_state()["nextRatifyState"]["nextEnactState"]["prevGovActionIds"]["Committee"]
        return None if previous is None else eratx.gov_id(previous["txId"], previous["govActionIx"])

    def hard_fork_root(self):
        previous = self.gov_state()["nextRatifyState"]["nextEnactState"]["prevGovActionIds"]["HardFork"]
        return None if previous is None else eratx.gov_id(previous["txId"], previous["govActionIx"])

    def round_with_votes(self, label, actions, cast, anchors=None):
        """Propose `actions`, wait for them, then vote: `cast(ids)` returns the votes and their signers."""
        txid = self.propose(label, self.gw[1], actions, anchors=anchors)
        if not txid:
            return None
        ids = [eratx.gov_id(txid, i) for i in range(len(actions))]
        casts, signers = cast(ids)
        return self.gov_votes(label + "-votes", self.gw[0], casts, signers)

    def second_round(self, pv):
        """A withdrawal and the hard fork to protocol version 11, both needing the committee (with `dynamic_forks`
        the withdrawal only: the protocol walk proposes the hard forks). In the extended
        run there are two competing hard forks: pool 3 alone votes for the first (pool 1's default is No for a
        hard fork even though it delegates to always-abstain), pools 2 and 3 for the second, which is enacted."""
        dreps = self.live_dreps()[:2]
        extended = self.ext is not None and self.ext.full

        def cast(ids):
            casts = [(eratx.voter("drep", k.hash), [(i, "yes") for i in ids]) for k in dreps]
            if extended:
                casts += [(eratx.voter("pool", self.pool_cold[2].hash), [(ids[1], "yes"), (ids[2], "yes")]),
                          (eratx.voter("pool", self.pool_cold[1].hash), [(ids[2], "yes")])]
                casts.append((eratx.voter("committee", self.hot[0].hash), [(i, "yes") for i in ids]))
                casts.append((eratx.voter("committee", self.hot[1].hash), [(ids[0], "no"), (ids[1], "yes"), (ids[2], "yes")]))
                return casts, dreps + self.pool_cold[1:] + self.hot
            if self.dynamic_forks:  # the withdrawal only: the committee splits, no pool has a vote on it
                casts.append((eratx.voter("committee", self.hot[0].hash), [(ids[0], "yes")]))
                casts.append((eratx.voter("committee", self.hot[1].hash), [(ids[0], "no")]))
                return casts, dreps + self.hot
            casts += [(eratx.voter("pool", p.hash), [(ids[1], "yes")]) for p in self.pool_cold[:2]]
            casts.append((eratx.voter("committee", self.hot[0].hash), [(i, "yes") for i in ids]))
            casts.append((eratx.voter("committee", self.hot[1].hash), [(ids[0], "no"), (ids[1], "yes")]))
            return casts, dreps + self.pool_cold[:2] + self.hot
        actions = [eratx.treasury_withdrawal_action({self.gw[3].reward: 2_000_000})]
        if not self.dynamic_forks:
            actions.append(eratx.hard_fork_action(11, 0, self.hard_fork_root()))
        anchors = None
        if extended:
            actions.append(eratx.hard_fork_action(11, 0, self.hard_fork_root()))
            anchors = [None, None, eratx.anchor("https://hardfork.invalid/second")]
        return self.round_with_votes("gproposals2", actions, cast, anchors=anchors)

    def third_round(self, pv):
        """No confidence in the committee. In the extended run no pool votes: pool 1 abstains, pool 2 votes
        yes and pool 3 no by default, and the lowered threshold lets that carry."""
        dreps = self.live_dreps()
        extended = self.ext is not None and self.ext.full

        def cast(ids):
            casts = [(eratx.voter("drep", k.hash), [(i, "yes") for i in ids]) for k in dreps]
            if extended:
                return casts, dreps
            casts += [(eratx.voter("pool", p.hash), [(i, "yes") for i in ids]) for p in self.pool_cold[:2]]
            return casts, dreps + self.pool_cold[:2]
        return self.round_with_votes("gproposals3", [eratx.no_confidence_action(self.committee_root())], cast)

    def fourth_round(self, pv, epoch):
        """Elect a new committee without an existing one: lower thresholds, no committee vote."""
        dreps = self.live_dreps()
        if self.ext is not None and self.ext.full:
            return self.ext.committee_election(dreps, epoch)

        def cast(ids):
            casts = [(eratx.voter("drep", k.hash), [(i, "yes") for i in ids]) for k in dreps]
            casts += [(eratx.voter("pool", p.hash), [(i, "yes") for i in ids]) for p in self.pool_cold]
            return casts, dreps + self.pool_cold
        return self.round_with_votes("gproposals4", [
            eratx.update_committee_action({self.cold[0].hash: epoch + 30}, threshold=(1, 3),
                                          prev=self.committee_root())], cast)

    def plutus_spend(self, pp):
        """Spend the locked output under PlutusV3 and mint a token under the same script."""
        _, _, by_addr = self.snapshot()
        locked = by_addr.get(self.script_addr, [])
        if not locked:
            return None
        w = self.gw[0]
        coll = [u for u in self.funds(w.address) if not u[2]]
        if len(coll) < 2:
            # split off a collateral output and try again in the next pass
            self.act("conway", "collateral-split", w, [(w.address, 50_000_000, None, {})], fee=500_000)
            return None
        collateral = coll[-1]
        units = [1_000_000, 400_000_000]
        fee = 1_500_000
        policy = self.script_hash
        mint = {policy: {b"v3token": 7}}

        def redeemers(sorted_inputs):
            spends = [(0, sorted_inputs.index(u[0]), 0, units) for u in locked]
            return spends + [(1, 0, 0, units)]

        models = self.v3_models(pp)

        def script_data(red, dat):
            red_cbor = eratx.enc({(t, i): [dd, list(u)] for t, i, dd, u in red})
            return eratx.script_data_hash(red_cbor, b"", models)

        return self.act("conway", "plutus-spend", w, [(self.gw[1].address, 4_000_000, {policy: {b"v3token": 7}}, {})],
                 mint=mint, spend_extra=locked, collateral=[collateral[0]], avoid=[collateral[0]],
                 plutus={3: [self.script]}, redeemers=redeemers, script_data=script_data, fee=fee)

    def plutus_ref_mint(self, pp):
        """Mint under the V3 script taken from a reference input (no script in the witness set)."""
        w = self.gw[0]
        coll = [u for u in self.funds(w.address) if not u[2]]
        if len(coll) < 2:
            return None
        collateral = coll[-1]
        units = [1_000_000, 400_000_000]
        policy = self.script_hash
        models = self.v3_models(pp)

        def script_data(red, dat):
            return eratx.script_data_hash(eratx.enc({(t, i): [dd, list(u)] for t, i, dd, u in red}), b"", models)

        return self.act("conway", "plutus-ref", w, [(self.gw[1].address, 4_000_000, {policy: {b"v3ref": 3}}, {})],
                        mint={policy: {b"v3ref": 3}}, collateral=[collateral[0]], avoid=[collateral[0]],
                        reference_inputs=[(self.proposed["lock"], 2)], redeemers=[(1, 0, 0, units)],
                        script_data=script_data, fee=1_000_000)

    def more_gov(self, pp, pv, epoch):
        """Updates, resignations, refunds and a donation."""
        gw, d = self.gw, self.gdrep
        certs = [eratx.drep_update_cert(d[1].hash, eratx.anchor("https://drep.invalid/1b")),
                 eratx.drep_unreg_cert(d[0].hash, pp["dRepDeposit"])]
        if pv >= 10:
            certs.append(eratx.resign_committee_cert(self.cold[1].hash, eratx.anchor("https://cc.invalid/resign")))
        certs.append(eratx.unreg_cert(gw[4].stake.hash, pp["stakeAddressDeposit"]))
        signers = [d[0], d[1], gw[4].stake] + ([self.cold[1]] if pv >= 10 else [])
        return self.act("conway", "gmore", gw[3], [], certs=certs,
                        refund=pp["dRepDeposit"] + pp["stakeAddressDeposit"], extra_signers=signers, fee=500_000)

    def donate(self):
        """A treasury donation that names the current treasury value."""
        return self.act("conway", "gdonate", self.gw[0], [], donation=1_000_000, deposit=1_000_000,
                        treasury=self.treasury(), fee=500_000)

    def start_extended(self, full=True):
        """Run the extended governance scenarios (govextra.py) in a thread of their own. `full` also
        changes the base governance rounds (competing hard forks, pools left to their default votes, the
        committee election); the light set only adds transactions and leaves the base rounds alone."""
        from . import govextra
        if self.ext is None:
            self.ext = govextra.Extended(self, full)

            def body():
                while not self.stop.is_set():
                    try:
                        self.ext.step()
                    except Exception as e:  # a scenario problem must not stop the run
                        self.log({"extendedFailed": repr(e)})
                    time.sleep(1.5)
            self.ext_thread = threading.Thread(target=body, daemon=True)
            self.ext_thread.start()

    # (era, epoch of the era, first slot of the epoch, last slot, action): actions that depend on the slot
    TIMED = [
        ("shelley", 1, 230, 400, "mir_mid_epoch"),
        ("shelley", 1, 385, 420, "mir_before_cutoff"),
        ("shelley", 1, 432, 480, "mir_after_cutoff"),
        ("shelley", 2, 60, 380, "mir_unregistered"),
        ("allegra", 0, 150, 400, "dereg_after_start"),
    ]

    def mir_mid_epoch(self, era):
        self.mir_cert(era, "mir-mid", 0, (self.wallets[1].stake.hash, 5_000_000))

    def mir_before_cutoff(self, era):
        """Still earlier than the slot 3k/f before the epoch ends: accepted, and replaces the earlier entry."""
        self.mir_cert(era, "mir-before", 0, (self.wallets[1].stake.hash, 9_000_000))

    def mir_after_cutoff(self, era):
        self.mir_cert(era, "mir-after", 0, (self.wallets[2].stake.hash, 2_000_000), expect="MIRCertificateTooLateinEpochDELEG")

    def mir_unregistered(self, era):
        """An entry for a credential that is not registered: it counts against the pot, is never paid."""
        self.mir_cert(era, "mir-unregistered", 0, (eratx.blake2b(b"never registered", 28), 4_000_000))

    def dereg_after_start(self, era):
        """After the reward calculation has started: the account was in the prefilter's set, so its
        reward goes to the treasury at the boundary."""
        self.deregister(era, "dereg-late", self.wallets[2])

    FEATURES = {"shelley": "shelley_features", "allegra": "allegra_features", "mary": "mary_features",
                "alonzo": "alonzo_features", "babbage": "babbage_features"}

    def epoch_batch(self, era, epoch):
        """The actions for one epoch of `era`: features first (once each), then transfers and withdrawals."""
        if era in self.FEATURES:
            getattr(self, self.FEATURES[era])(era)
        if era == "conway":
            self.conway_features(epoch)
        if era in ("alonzo", "babbage", "conway") and self.once(f"legacy-plutus-{era}"):
            from .eraplutus import exercise
            exercise(self, era)
        if era in ("alonzo", "babbage", "conway") and self.once(f"auxiliary-plutus-{era}"):
            from .eraauxiliary import exercise
            exercise(self, era)
        if era in ("alonzo", "babbage", "conway") and self.once(f"refusals-{era}"):
            from .erarefusal import exercise
            try:
                exercise(self, era)
            except Exception as e:  # evidence only: a failed capture must not stop the cluster walk
                self.log({"refusalsFailed": era, "error": repr(e)})
        self.transfers(era, 2)
        if era != "conway":  # the ledger-state layout the withdrawals read is the pre-Conway one
            self.withdrawals(era)

    # ------------------------------------------------------------ the worker
    POINTS = [("mid", 230), ("late", 440)]

    def run(self, compare, conway_hook=None):
        """Worker-thread body. Per epoch: compare right after the epoch boundary, run that epoch's
        transaction batch, compare, compare again mid-epoch and shortly before the next boundary.
        `compare(era, label)` records one comparison and never raises."""
        seen = set()
        while not self.stop.is_set():
            try:
                tip = self.epoch_info()
            except Exception:
                time.sleep(1)
                continue
            era, epoch, in_epoch = tip.get("era", "").lower(), tip.get("epoch"), tip.get("slotInEpoch", 0)
            if era in ERAS and self.ready.is_set() and "slotInEpoch" in tip:
                self.tip = tip
                key = (era, epoch)
                if era == "conway" and conway_hook and self.once("conway-hook"):
                    conway_hook()
                if (key, "batch") not in seen and in_epoch >= 3:
                    seen.add((key, "batch"))
                    compare(era, "start")
                    if True:
                        try:
                            self.epoch_batch(era, epoch)
                        except Exception as e:
                            self.log({"batchFailed": era, "error": repr(e)})
                        compare(era, "after-batch")
                self.era_first.setdefault(era, epoch)
                for first, ordinal, lo, hi, action in self.TIMED:
                    if era == first and epoch - self.era_first[era] == ordinal and (key, "batch") in seen \
                            and (key, action) not in seen and in_epoch >= lo:
                        seen.add((key, action))
                        now = self.epoch_info()
                        if now.get("epoch") == epoch and now.get("slotInEpoch", 0) <= hi:
                            try:
                                getattr(self, action)(era)
                            except Exception as e:
                                self.log({"actionFailed": action, "error": repr(e)})
                        else:
                            self.log({"skipped": action, "reason": "window missed", "at": now.get("slotInEpoch")})
                for label, at in self.POINTS:
                    if (key, "batch") in seen and (key, label) not in seen:
                        now = self.epoch_info()
                        if now.get("epoch") == epoch and now.get("slotInEpoch", 0) >= at:
                            seen.add((key, label))
                            compare(era, label)
            time.sleep(0.7)
