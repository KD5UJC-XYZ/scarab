# Native invalid-block punishment

Consensus pin `82ecba329d7d054340bf707d44fe6e9ac27cec40` is the reference. The
[observer](../../../scripts/oracle/genesis-punishment/Main.hs) invokes the actual
`initialTentativeHeaderState` and `applyTentativeHeaderView` instances, including the
hard-fork combinator. All 72 traces and 4,178 steps retain the complete native state.
The domain is Scarab's currently supported Byron through Conway eras. The pin also
contains future Dijkstra code; no Dijkstra input is exported or discarded.

The cases cover Byron EBB ties, block-number boundaries, all supported Shelley-era
issuer/opcert identities, forward/backward era transitions and 64 deterministic
supplementary arithmetic schedules. These supplementary schedules are observer inputs,
not a claim to import an upstream `Arbitrary` generator. No exported step is filtered.

`projection.json` decodes the original signed header in the first maintained preview
Conway block with native `decodeShelleyHeader`, then calls actual `tentativeHeaderView`.
Its block provenance and exact header bytes are retained. The same header exercises
actual opaque `mkForDiffusionPipelining`, `branch`, `enact` and `mkPunishThisThread`:
invalid-prefix punishment, first trap forgiveness, repeated trap punishment and reset
for a newly created BlockFetch client. Native configuration is a non-forging Conway
configuration with protocol version 9, network magic 2 and no issuer signing keys;
this header view does not inspect configuration. This comparison establishes projection,
not an additional signature-validation claim.

The [ChainDB observer](../../../scripts/oracle/genesis-punishment/MainClassification.hs)
opens actual native ChainDB instances using the upstream EBB-capable storage test block
and ledger. Disposable real threads observe the opaque punishment callbacks. All inputs,
processed results and selected chains are retained for four cases: invalid added block,
invalid prefix of a newly preferred fork, later invalid suffix and valid same-slot EBB
before an invalid regular suffix. Classification input points identify those constructed
blocks; the punishment outcome comes from ChainDB, not a copied classification function.
The pure tests additionally check the equal-slot distinction directly against
`ChainSel.hs:1326–1341`; its comment explicitly rules out three consecutive same-slot blocks.

Reproduce on an Euler/Rutherford compute host, with the pinned source checkout and
retained dependency image available:

```sh
python3 scripts/oracle/genesis-punishment/export.py \
  --source /path/to/pinned/ouroboros-consensus \
  --probe-dir /tmp/scarab/punishment-native \
  --output-dir /tmp/scarab/punishment-output \
  --container scarab-punishment-native \
  --allow-download
```

The exporter builds from an untouched source archive in its own Docker container.
`manifest.json` records source hashes, both observer hashes, exporter hash, image ID,
both executable hashes, Cabal plan hash and every maintained output hash. Original
source functions are unchanged. The recorded fresh export exactly reproduced all
three payloads; Scala compares every native transition and all four classification
outcomes. These bounded cases do not claim exhaustive seed coverage or identical
thread timing.
