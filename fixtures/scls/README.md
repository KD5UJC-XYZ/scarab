# SCLS reference fixtures

Status: reference evidence for the in-progress binary import/export implementation (#77), not a
complete CIP-0165 or native restart compatibility claim.

`conway-4593/reference.scls` was produced by the unchanged canonical-state exporter at
[cardano-ledger f649f975](https://github.com/IntersectMBO/cardano-ledger/tree/f649f9751074d2ab3de033fc3912f29c9862c1f5/libs/cardano-ledger-canonical-state), using the unchanged
[SCLS reference serializer at 8e2bba94](https://github.com/tweag/cardano-cls/tree/8e2bba9442cb388089f53d708cc4aedcb47726ea).
The source is the committed `fixtures/haskell-state/conway-4593` snapshot, with **all three external
UTxO entries** attached to its original NewEpochState. Timestamp is fixed at 2026-10-18T00:00:00Z;
slot 4593, epoch 10; raw chunks, reference 16 MiB buffer. The result is 6,060 bytes, 16 entries,
eight chunks and twelve declared namespaces (four empty). `manifest.json` and `roots.tsv` record the
actual roots; `entries.tsv` contains the original namespace/key/value bytes, independently extracted
and verified by `scripts/oracle/scls/verify.py` using Python's Blake2b-224.

`provenance.json` pins sources, image, compiler, dependency-plan hashes, original inputs and actual
reference outcomes. The Haskell package is development canonical-state 9.9.9.9 built against the
released ledger packages Conway 1.23.0.0, Babbage 1.14.0.0, Alonzo 1.16.0.0, Core 1.21.0.0,
Binary 1.9.1.0 and Plutus 1.70.0.0. This is the explicit `ledger-f649-conway-v0` profile, whose twelve
namespaces differ from the draft CIP's sixteen; it does not implement the draft nonces or mark/set/go
namespaces. The reference VRF namespace is `entities/stake_pools/vrf_key_hashes/v0`.

The fixture helper is `scripts/oracle/scls/Main.hs`, run as an internal executable of the
canonical-state package because its Conway library is private. Only its Cabal executable declaration
was added; no reference state, import, hash or serializer implementation was patched. It reads
`/probe/input`, writes `/probe/reference-conway-4593.scls`, imports every namespace into the complete
same-point base, and writes `/probe/reexport-conway-4593.scls`. `extract.py` prepares those original
inputs in a new destination. Run Haskell in Docker, with that workspace mounted at `/probe`.

The re-export is **byte-identical**, the entire non-UTxO typed state is equal, and every output's
on-chain CBOR bytes are equal. Full NewEpochState bytes and stock Haskell equality are **not** equal:
two outputs change from compact-address to optimized address/hash MemPack constructors. The internal
constructors are distinguished by stock equality. This negative result remains explicit; it is not
reported as byte-identical native storage or a chain-continuation proof. Nonempty DRep/proposal
controls and Scala import/export integration remain part of #77.

`namespace-value-vectors/conway-f649.tsv` adds small typed Haskell controls, including nonempty
DReps, proposals, pool registrations/retirements/future parameters, and unbounded canonical execution
units. Its adjacent JSON records the exact generator, source pins and hashes. These synthetic typed
values are separate from the historical snapshot artifact above.

`dormancy/f649.tsv` records ten calls to the unmodified pinned Haskell
`updateDormantDRepExpiry` using the helper `scripts/oracle/scls/Dormancy.hs`. Columns are current
epoch, dormant epochs, original expiry, normalized expiry and normalized dormant counter. It
includes two distinct original expiries with the same canonical result at Word64 wraparound. The
Scala mapper therefore checks and retains the known original expiry from its complete same-point
base, rather than guessing an inverse by subtraction. The artifact omits the dormant counter;
normalizing it away would lose restart state. These tests do not establish whole-state admission or
authorize publication, which remain part of the import service.

`proposal-roundtrip/f649.tsv` distinguishes wrapper roundtrip from actual ledger-state import and
re-export. The unmodified pinned `toGovActionState`/`fromGovActionState` normalize the synthetic
nonminimal proposal deposit, so the two recorded byte strings are deliberately unequal. Scarab
must match this state-conversion behavior rather than adding a stronger native byte-retention
contract. Adjacent JSON pins the helper, inputs, sources and actual output hashes.

The framing tests deliberately distinguish complete raw records from a complete valid SCLS file:
a prefix ending at a record boundary is framing-valid but cannot pass the later manifest/schema
validation required before publication.
