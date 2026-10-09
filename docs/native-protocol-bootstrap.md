# Original protocol header bootstrap candidate

This package-private candidate binds the v2 acquisition contract at
`15ae0462930cb45cf4a07446059ea7823d5e0802` to a narrow header bootstrap. It
does not establish that an acquisition occurred, authenticate a snapshot, admit
a native reward seed, or activate a runtime/CLI profile. Actual native capture
and native conformance remain pending. Monetary parity and full epoch semantics
are not checked. Source review clears preparation only.

## Original evidence and anchor binding

The caller must provide these eight original files and eight independently
expected SHA256 pins, with exactly the same filename set:

- `request.json`
- `capture.json`
- `native-verification.json`
- `receipt.json`
- `original-debug-epoch.cbor`
- `original-whole-utxo.cbor`
- `derived-full-epoch-seed.cbor`
- `original-debug-protocol.cbor`

The expected non-origin anchor contains hash, slot and block number. Request,
acquired and final points must match both hash and slot; both block brackets
must match the expected block number. Exact version and single-acquire/no-reacquire
lifecycle fields are mandatory. Capture hex must equal the original bytes.
Receipt and verifier pins must agree with the external pins. A self-consistent
rewritten packet with newly supplied pins has a different source identity;
hash consistency is not evidence of native execution or peer attestation.

The reused native verifier concerns epoch/UTxO reconstruction only. Its
`protocolSemanticsVerified` receipt flag remains false. Scala protocol parsing
does not retroactively extend that verifier's scope.

Each bootstrap CBOR original is capped at **512 KiB**, stricter than the v2
transport's 8 MiB limit. Request JSON is capped at 16 KiB; other JSON and effective
genesis inputs at 4 MiB; the eight-file aggregate at 8 MiB. The existing JSON
parser's additional string/depth limits still apply. Originals remain distinct;
no legacy input files are fabricated from decoded values.

## Exact protocol and leadership decoding

The inspected Praos serializer uses this CBOR shape:

```text
[0, [
  [1, lastSlot],
  { issuerHash28: counterUInt64, ... },
  evolvingNonce, candidateNonce, epochNonce, previousEpochNonce,
  labNonce, lastEpochBlockNonce
]]
nonce = [0] | [1, hash32]
```

The outer pair is `encodeVersion 0`; the inner record has exactly eight fields.
`WithOrigin` encodes Origin as `[0]` and At as `[1, slot]`; this profile rejects
Origin. All six nonce roles are read independently. No missing field is inferred
from another role, and no nonce defaults are inserted. A mandatory explicit
previous-epoch Neutral becomes `Some(Neutral)`. The unchanged JSON snapshot
format still represents a missing previous-epoch nonce as `None`, meaning
unknown. Full consumption, uint64 ranges, hash widths and duplicate counter
issuers are checked. The standalone protocol decoder has an 8 MiB input cap,
depth 8 and at most 4096 counters; the combined bootstrap applies its smaller cap.

Leadership comes from the original seven-field NewEpochState's `nesPd`:
`[poolMap, totalActiveStake]`, with each pool value
`[tag30 relativeStake, absoluteStake, vrfHash32]`. This profile requires
1-4096 unique pools, positive total, reduced fractions in [0, 1], exact fraction/
amount/total equality and an exact sum of amounts. Its CBOR parser rejects floats,
undefined and unassigned simple values **anywhere in the enclosing epoch state**.
Consequently it cannot accept arbitrary native epoch states, including ones
containing float-encoded non-myopic data. Governance-augmented distributions that
retain old fractions also fail this narrow check; rejection does not establish
native invalidity.

The separately pinned original effective genesis must match the acquisition's
testnet network magic. Epoch geometry, KES period/lifetime and active-slot
coefficient feed existing checked header contexts. The epoch and protocol slot
must agree with the full external anchor. Leadership registration/VRF hashes and
stakes are bound to their original epoch source; operational-certificate counters
seed the certificate state and must match the nonce binding's counter map.
Genesis, acquisition, leadership, certificate, nonce and eligibility identities
remain linked. These are checked header inputs, not complete ledger-seed admission,
historical authority or cryptographic verification of the capture.

## Inspected source pins

SHA256 values below identify inspected archive members, not executed binaries.

| Package and member | SHA256 |
| --- | --- |
| ouroboros-consensus 4.2.1.0, Protocol/Praos.hs | `b770f0c73f2c34dd69146f2b087a786d7d937d119f0efb961ff6757105119e23` |
| ouroboros-consensus 4.2.1.0, Util/Versioned.hs | `4671724606e6335c7b962ed76ce9081b31d7496246423ba650a207f12ee164a6` |
| cardano-slotting 0.2.2.0, Cardano/Slotting/Slot.hs | `d2d80973b37f37b79df398e316090a41528b6732c6e5955e794b77e2a00ba4a7` |
| serialise 0.2.6.1, Codec/Serialise/Class.hs | `d4326103c2fa5348fc26e1f99330cda93b5ac59ed123780712b1bcde71c2fc01` |
| cardano-ledger-core 1.21.0.0, Ledger/BaseTypes.hs | `0b86d1ecdd5abb0cf89194e7324fe1f62e58214cbd7386a1c50aeaf1c605866e` |
| cardano-ledger-core 1.21.0.0, Ledger/Hashes.hs | `0bf9227a0f8144c7ee9bc89c13dc936990a768322ed1af858079cc9401aef966` |
| cardano-binary 1.9.1.0, Cardano/Binary/ToCBOR.hs | `8300641705742281dc2afa9f4cb8a9064751eb76666fae8d5c061cfa114cd428` |
| cardano-ledger-shelley 1.19.0.1, Shelley/LedgerState/Types.hs | `f619314d1e920cfd3dc154474c5d06f486a8f6f0655061f6b26d71d99e848440` |
| cardano-ledger-core 1.21.0.0, Ledger/State/PoolDistr.hs | `cee8dace9213af829c54f75e8c24b7e01e029a2e28252209d7842b4a11d702df` |

Praos/Versioned define the versioned record; Slot/Serialise define WithOrigin;
BaseTypes defines nonce constructors; Hashes/ToCBOR define issuer widths and map
encoding. Types/PoolDistr define the leadership projection. The acquisition
packet separately pins the actual DebugChainDepState query/encoder source.

## Preparation plan for acquisition packet 15ae046

No build or execution permission follows from this document or its source review.

1. Verify the exact executable-target dependency closure and all package/source
   pins for the new query and protocol-offline targets. Sharing package versions
   with v1 does not establish the new target closure or compilation.
2. For the separately scheduled native tests, use an isolated bounded offline build/run with
   at most **2 CPU / 2 GiB**, a private cache/output directory, explicit wall-clock
   limits and output limits. Preserve the 45-second packet supervisor and 64 MiB
   output ceiling. Native payload checks occur **after framework decoding**;
   external memory, timeout and output controls are necessary allocation bounds.
3. Compile the reviewed native targets, then execute both typed-session and
   encoded Unix-peer tests against the pinned binaries. Python transport tests
   without those binaries explicitly skip native coverage. Synthetic Scala/Python
   successes cannot substitute for these pending checks.
4. Preserve reviewed execution provenance before any separately authorized native
   capture. Actual point-bound acquisition, full supported seed admission and
   runtime activation remain separate pending steps.

The pending user approval specifically concerns the seed container execution-permission
correction. Do not reuse that blocked launcher or change its execution permissions.
Native compilation and peer tests have not run in this bootstrap task. Existing CLI
epoch guards and checkpoint refusals remain intact.

## Focused candidate verification

The focused offline check passed **33 tests (19 core, 14 app)**. The retained log
SHA256 is `1fdc48b9dcbbf51864417617104a9af6eccc8b0ce0ece8ca46a08c04e1344452`.
This validates the candidate's synthetic decoding, source binding and mutation
checks; it does not establish native acquisition, native-target compilation or
cross-language protocol conformance. Execution receipts remain private.
