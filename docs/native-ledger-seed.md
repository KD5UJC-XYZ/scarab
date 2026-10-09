# Internal checked native ledger-side join

`NativeLedgerSeed.bind` joins the existing checked epoch components, normalized
governance and exact parameter/global bridges. It retains their decoded objects
and requires one original seed, debug epoch, whole UTxO, point and source identity.
It is an internal diagnostic result, with no runtime import or admission capability.

The join compares shared original component bytes and hashes, account domains and
reward/deposit/pool projections, votes and reverse delegation, pools and snapshots,
deposit obligations and supply, all four temporal parameter originals, exact reward
Absent and non-myopic state. Existing bounded decoders and the pure finite governance
validator supply these checks; the join does not introduce a second native parser.
The governance validator is used for consistency, not as evidence that a boundary
was executed against the audited seed.

The audited bundle retains outer current C / previous P and historical current P /
previous P with NoUpdate. The historical empty DRep snapshot stays distinct from
the three current registrations. No original is rewritten to manufacture agreement.

## Remaining crossing blockers

The actual bundle reports exactly missing point-bound protocol-v2 evidence and
incompatible crossing geometry. Its epoch length is 500, security parameter 5 and
active-slot coefficient 0.05; the randomness window is 400, so the required
`2 * randomnessWindow < epochLength` condition fails. The constructor cannot clear
the protocol blocker or enable crossing. A separately checked fresh capture remains
necessary; there is no protocol supervisor or permission change in this work.

Reward state is explicitly Absent, so no historical reward freeze is required or
fabricated. Source authentication, complete parameter/cost-model validity and live
governance cursor recovery remain standing limitations, separate from this blocker
list. Hashes bind supplied sources; they do not authenticate them.

## Verification

Default generated fixtures exercise valid joins and reject coherent source splices
and overlap mismatches with individually valid hashes. Opt-in tests use the seven
hash-pinned audited originals mounted read-only, assert the exact blockers and
temporal roles, and reject a foreign whole-source governance object even though its
component spans and recomputed hashes are valid. Original captures remain private.

Final offline verification passed **119 Scala tests** (94 app, 25 ledger),
including both explicitly enabled audited join tests and the five existing audited
component tests, plus formatting checks. The run used 2 CPU/2 GiB, no network, a
private cache and only seven immutable original files mounted read-only. All seven
external hashes were checked before and after execution; owned container cleanup
was confirmed. No native executable, cluster or protocol supervisor was run.

Test log SHA256: `dd20d7e0037f02744dea95696e10bd33176f9fcccb5368c8de8d72729729c2f3`.

## Schema-preserving v2 adapter

`NativeLedgerV2.decode` consumes exactly ten externally pinned files: the eight
`NativeProtocolBootstrap.InputNames`, `native-projection.json` and
`effective-shelley-genesis.json`. It checks the v2 acquisition and passes the same
original request, capture, debug epoch, whole UTxO and seed bytes into
`NativeEpochComponents.decodeV2`. The v1 entry point remains separate. Neither
entry point rewrites schema fields to fit the other.

The resulting ledger identity includes the complete checked acquisition identity,
including its original protocol and receipt evidence. The adapter joins ledger,
protocol and acquisition by their expected identities and full point, exact
genesis/network, leadership bytes, certificate registrations and bounds, explicit
nonce state and eligibility stake distribution. Existing bounded decoders supply
the projections. The adapter clears only `MissingPointBoundProtocolV2`; it retains
`IncompatibleCrossingGeometry` whenever twice the randomness window is at least the
epoch length. Geometry success grants no runtime capability.

All runtime import, reward admission, source authentication, actual acquisition,
full ledger validation and native conformance flags remain false. Scripted native
protocol tests establish client behavior against test peers; they are not a fresh
capture or proof of live ledger agreement. The retained 500-slot bundle still has
both of its original crossing blockers.

### Fresh-input test contract

`NativeLedgerV2AuditedSuite` registers a test only when
`NATIVE_LEDGER_V2_BUNDLE` names a directory containing the ten originals and
`adapter-inputs.json`. `NATIVE_LEDGER_V2_MANIFEST_SHA256` must independently pin that
manifest. Its exact schema is:

```json
{
  "schema": "native-ledger-v2-reviewed-inputs-v1",
  "point": {"hash": "<64 lowercase hex digits>", "slot": 0, "blockNo": 0},
  "inputs": {
    "<each of the ten exact input names>": {
      "sha256": "<64 lowercase hex digits>",
      "bytes": 0
    }
  }
}
```

Values above describe the shape, not a usable capture. The test requires the fresh
epoch-zero point before slot 300, epoch length 1000, randomness window 400 and exact
reward Absent bytes. It checks original preservation and no crossing blockers,
while retaining all diagnostic limitations. Without the environment and reviewed
inputs, the suite contributes no executed actual-input test. Raw captures and the
manifest remain private. An eight-file protocol packet alone is insufficient:
the matching native projection and exact effective genesis are also required.

### Adapter verification checkpoint

The focused offline run passed **139 Scala tests** (114 app, 25 ledger), including
the seven generated v2 adapter tests, thirteen existing protocol bootstrap tests,
two retained audited ledger-join tests and five retained audited component tests.
Formatting checks passed. The new fresh-v2 audited test was not registered because
no fresh reviewed manifest was supplied; none of these counts imply a new capture.

The run used 2 CPU/2 GiB, no network, a private cache and seven retained originals
mounted read-only and hash-checked before and after. Owned container cleanup was
confirmed. The first compile failure, caused by a helper shadowing `identity`, was
preserved before its one-line correction and successful retry. No runtime or native
cluster was enabled, and this adapter checkpoint was not published.

Test log SHA256: `08c61d887d97fbc27d1cbe7849762031486a0f34013c4658fd9ea28f74fb13a9`.
