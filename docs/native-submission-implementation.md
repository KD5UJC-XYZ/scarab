# Isolated native-script submission implementation

The opt-in `isolated-conway-pv9-ada-native-v1` profile accepts a bounded native
script spend through the existing Scala HTTP API, volatile pool and TxSubmission2
relay. A signature-only spend passed the isolated reference inclusion and exact
endpoint comparison on 2026-10-09. The executable remains Test-only, app-private
and loopback-bound. This is not a production node API or full ledger/consensus
validation.

## Implemented scope

The owner fixes a closed admission profile for its lifetime. Complete StatePins,
candidates, HTTP results, admission and pool revalidation carry that profile.
The default ADA-vkey profile remains unchanged. Cross-profile candidates fail
closed even if a generic pin would otherwise compare equal.

Native admission requires at least one confirmed enterprise native-script input,
optionally with confirmed key inputs, and creates only scalar-ADA key-payment
outputs. It checks the supported signature/all/any/threshold/timelock predicates,
original script hashes, original-body signatures, validity, fees and minimum
outputs. The [contract](native-submission-contract-v1.md) records exact fields and
bounds. Script-output creation, Plutus, multiasset/mint, datums, reference inputs
and scripts, collateral, withdrawals, certificates, governance and auxiliary data
remain outside this profile.

The pool retains generation fencing, atomic reservations, bounded original-byte
leases and follower-only inclusion. Admission does not publish a hypothetical
ledger state. Accepted, delivered and included remain distinct states.

## Recorded isolated acceptance

The reviewed controller uses node 11.1.3, CLI 11.2.3.0, testnet 11.1.1 and ledger
PV9, with the existing explicit `TxSubmissionInitDelay=0` fixture setting.
Reference networking is `none`; Scala and its HTTP client join only that exact
container namespace. The combined ceiling is 4 CPU / 7 GiB, with serial helpers,
240 seconds for operation and 30 seconds for cleanup. No public network or real
funds were used.

Reference-only setup submitted exactly one funding transaction, creating the
20,000,000-lovelace script input. Complete before/after UTxO, fee delta, original
transaction identity and full points were checked before sealing the reference
submission gate. The CLI snapshots are separate acquisitions under verified
keyless quiescence, not one atomic query. The actual native bootstrap packet was
then acquired at slot 196, block 4; its kind-7
MemPack/whole-UTxO binding and full native initial decoder both passed. Funding
does not count as Scala ingress acceptance.

The tested spend then followed this path:

- Scala HTTP returned `202 Accepted`; exact duplicate returned `200 AlreadyPresent`.
- Scala TxSubmission2 announced and wrote the bounded original transaction.
- The applied follower reported inclusion at slot 459, block 8,
  and removed the transaction from the pool.
- Original body and witness spans matched the submitted transaction independently.
  Outer transaction-envelope byte equality is not claimed.
- The same held Scala state crossed epoch 1 and matched the exact native endpoint
  at slot 1004, block 26, including the supported complete
  ledger, protocol and governance comparison.
- Runtime resources finalized and invocation-owned containers were removed.

Four relay sessions opened. The existing `relayAttempts` counter also counts
the refused fifth attempt; it must not be read as five opened sessions.

Transaction ID: `c34402a86e45096b56c23cc901cd8d2fe8ba44e7ec9eeae24d9571c98da1319b`.

| Private retained receipt | SHA-256 |
| --- | --- |
| Controller result | `d23c2d43d772dde02c7bddfcb740a03b2c2a2cc7c9ea23ab592016ca4b5d5857` |
| Scala result | `3aff023fd74d28e8078da0e9ff90e99df522d52c7a6afc7133dbd5838d313e0e` |
| HTTP client result | `7ad34cc889605a64515659d5fa323a9bbe8d46bc733f1a6c7b396784224ae90e` |
| Funding comparison | `23218a4afb08d5fe5ac339629005197c9f2372daa25ab87b61e817917fc4b871` |
| Native bootstrap proof | `d8af16379b587f5bae79e27c6c33b71316f01639e00a41b404054f8872d0d36a` |

Raw logs, disposable keys, databases and captured originals remain outside Git.
The first attempt stopped before funding because the pinned CLI's nullable
`inlineDatumRaw` field was unrecognized; the fixture now accepts only null for
that field. The second observed funding but refused a post-funding slot of 302,
outside the unchanged `<300` bound. Both failed invocations and cleanup receipts
are retained. The third passed with unchanged timing bounds; the preparation
window is intentionally strict and remains sensitive to local timing.

## Verification and remaining work

Fresh-source offline regression passed **1,619 Scala tests, all 25 public serial
gates, and 393 Python tests run with two expected skips**. The subsequent nullable
CLI-field correction passed its focused 14-test suite. A separate retained-data
run passed 43 Scala tests, including all five funded-bootstrap tests through the
actual initial decoder. Its synthetic mutations preserve untouched original
spans and are explicitly test scaffolding, never native acquisition evidence.
Independent source and evidence review accompany this implementation.

The live positive uses one signature script. The broader native predicate matrix
is tested offline; it does not establish reference agreement for every case.
Strict negative agreement still needs a typed reference receipt with an
authoritative evaluation slot and same-state binding. A CLI rejection or nonzero
exit is insufficient. No such oracle agreement is claimed here.

Full ledger/consensus validation, native conformance, live reward-pulser cursor
equality, default 60-second reference initialization-delay interoperability,
restart durability and a Plutus admission profile remain unproved or unsupported.
Future Plutus work needs separately versioned phase-one/phase-two, language/cost
model, context, budget and collateral evidence using the same Scala ingress.
