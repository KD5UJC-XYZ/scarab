# Restricted validator storage contract: first pure slice

This work begins a versioned codec for the restricted validator. It does **not**
serialize a complete validator, restore a runtime, write files or establish crash
recovery. `RestrictedLedgerImage` is the implemented ledger/provenance slice.

## Identity and existing limitations

`CoherentSequence.State.id` combines context, acquisition, certificate, nonce,
eligibility and ledger identities, plus optional stake, rewards, epoch and boundary
components. These identities include lineage and opaque historical inputs. The
existing `LocalDerivedCheckpoint` v2 trusted anchor contains certificate, nonce,
eligibility and ledger local images, but not the complete stake/reward/boundary
composition. Its external controller authority attests discarded history; decoding
and content checks cannot recreate that evidence. Reusing that codec for Plutus
stake state or fabricating its historical IDs would be incorrect.

Eventual restore should authenticate a complete semantic anchor and its original
sources, reconstruct checked components and create fresh runtime/owner identities.
Persisted old IDs are historical provenance, not a requirement to fabricate equal
new runtime IDs. A restore bridge must name the authorized old semantic anchor and
new checked identities. Pending pool entries, leases, owner fences and live undo
capabilities must not be deserialized as authority.

## Complete-state contract still requiring agreement

A future complete image must be exported from **one locked coherent cell** and
include all of these mandatory versioned components:

1. Exact genesis/config, acquired parameter records, source manifest and acquisition
   evidence; private network, profile, epoch geometry, full anchor and terminal points.
2. Complete original UTxO/output/datum bytes, fees and every supported pot; checked
   environment projection bound to those exact originals.
3. Certificate counters and semantic full point, complete nonce evolution state and
   eligibility evidence with explicit historical-versus-replayed provenance.
4. Stake UTxO projection, instantaneous stake, mark/set/go snapshots, snapshot fees,
   accounts, pools and delegations needed by the supported transition. Cross-check
   all redundant representations, not only their self-declared digests.
5. Represented protocol components; explicit capability bitmap rejecting unsupported
   rewards, governance, epoch crossing or boundary state. Absence is not a default.
6. Bounded exact original header/block suffix, semantic anchor, revision and rollback
   floor. Replay rebuilds fresh undo capabilities; rollback below the floor rejects.

No complete-image version is accepted yet. Proposed complete-state collection and
restore signatures must be agreed with the runtime owner before shared edits.
The first bridge should be a pure, private checked exporter from one coherent
snapshot, followed by a separately reviewed source-authenticated reconstruction
path. Opaque byte slots must not be promoted into checked state.

## Implemented wire slice

New files exclusively owned by this workstream:
`ledger/.../storage/RestrictedLedgerImage.scala`, its matching suite, this document.
No shared runtime or existing codec changes.

Binary format (big endian): eight ASCII magic bytes `RSLIMG01`, uint32 version 1,
uint32-length-prefixed ASCII profile, uint64 private magic and epoch, uint64 slot
and block number, 32-byte point hash and environment ID; then fixed ordered
`genesis`, `parameters`, `manifest` source entries, each with SHA256 and uint32
length plus original bytes; uint128 fee pot, uint32 length plus original complete
UTxO CBOR; finally SHA256 of every preceding byte. Fixed ordering prevents duplicate
or unknown source names. All payload bytes are retained, never normalized.

Only the existing isolated PV9/V3 spending profile, testnet address network 0,
epoch 0 and slots 1..999 are accepted. Block number zero is valid. Bounds: 3 MiB
whole wire image, 1 MiB UTxO/genesis, 256 KiB parameters, 64 KiB source manifest;
UTxO parsing retains the existing depth32/items4096 and output/datum bounds.
Declared lengths are checked before allocation. Unknown version/profile, trailing
bytes, malformed outputs, duplicate semantic UTxO keys and changed checksums reject.

`encode` accepts supplied facts, not a coherent-state proof. `decode(original,
expectedBinding)` requires a separately supplied exact profile/network/epoch/full
point/environment/source-pin binding and returns `UntrustedImage`. Sources remain
uninterpreted original bytes in this slice. A caller supplying expectations read
from the image has established no authentication. Checksums provide corruption
detection only; freshness/rollback authorization requires independent policy.
A valid fee/UTxO substitution with a recomputed checksum can still pass structural
decode: the external publication authority must pin the **whole image digest**, not
just the source binding. A test makes this non-authentication boundary explicit.
Semantic source parsing and component cross-validation remain required before
runtime use. No persisted coherent, ledger-head, owner or nonce IDs are invented.

## Persistence and verification boundary

Pure tests cover exact output/datum/source retention, roundtrip, every truncation,
every single-byte corruption, repaired-checksum malformed versions/lengths/trailing
payload, wrong expected bindings, semantic duplicate inputs and numerical limits.
They use synthetic source bytes explicitly, not acquired native parity evidence.

There is no file store here: no atomic publication, file fsync, **directory fsync**,
controller acknowledgement, crash/restart, lease fencing, replay restore or rollback
test. No crash-durability claim is justified. Before such a claim, test each write /
file-sync / rename-or-link / directory-sync / external-ack boundary under failure,
verify independent anti-rollback authority, and restore/replay the complete semantic
state with fresh identities. Main owns that integration and the sustained service.
