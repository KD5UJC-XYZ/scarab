# Era-state projection digests

Status: Current — project projection regression goldens, with historical Byron digests retained.

`eras.tsv` records `index TAB era TAB block number TAB SHA-256` for 731 states in the
[era-walking capture](../eras/README.md). `ProjectionSuite` requires the exact row inventory and
metadata and compares all 681 Shelley-based digests. The 50 historical Byron digests included a
placeholder ledger that no longer belongs in the current projection; they are preserved unchanged,
not presented as current expected values or silently regenerated.

For each Byron state, the test instead derives the entire expected current JSON independently
of `Projection.node`: the point comes from the original header CBOR, its prefixed Blake2b-256
hash, and the original genesis `10*k` epoch length. The header wrappers are pinned in
[cardano-ledger f649f975, Header.hs](https://github.com/IntersectMBO/cardano-ledger/blob/f649f9751074d2ab3de033fc3912f29c9862c1f5/eras/byron/ledger/impl/src/Cardano/Chain/Block/Header.hs#L489).
While still in Byron, Scarab's read path exposes the **prepared Shelley state**, not the current
PBFT signer window ([`ChainRead.depState`](../../node/src/main/scala/xyz/kd5ujc/scarab/node/read/ChainRead.scala) selects `MirrorExtras.InByron.pending`). Its epoch/evolving/candidate nonce values are the Blake2b-256 of the exact
Shelley genesis bytes; lab and last-epoch-block nonces are null, and operational-certificate
counters empty. Pinned
[consensus CanHardFork.hs:405–417](https://github.com/IntersectMBO/ouroboros-consensus/blob/82ecba329d7d054340bf707d44fe6e9ac27cec40/ouroboros-consensus-cardano/src/ouroboros-consensus-cardano/Ouroboros/Consensus/Cardano/CanHardFork.hs#L405)
establishes those initialization values, and
[genesis nonce derivation:59–80](https://github.com/IntersectMBO/ouroboros-consensus/blob/82ecba329d7d054340bf707d44fe6e9ac27cec40/ouroboros-consensus-cardano/src/unstable-cardano-tools/Cardano/Node/Protocol/Shelley.hs#L59)
hashes the original file. The test independently derives and pins the nonce
`87b08ba08e075a9cdf610b78cbaa03d7e6752b768c90403a1fd2179f7dc1ba78`.
This is a source-backed **local projection contract**, not captured native PBFT state parity.
It checks every projected value and excludes unexpected extra fields, but does not inspect the
complete PBFT signer window or Byron ledger.

Do not refresh these digests to make a test pass. Preserve the old file, establish an independent
reference-backed reason and a field-level before/after comparison, then review a separate candidate.
The recording entry point requires an explicit, nonexistent candidate path and refuses the
tracked original path (including normalized aliases). It copies all historical Byron rows verbatim
without invoking their current projector and writes with `CREATE_NEW` so an existing file cannot
be overwritten. In the Docker JDK:

```sh
bash scripts/sbtw 'node/Test/runMain xyz.kd5ujc.scarab.node.read.ProjectionGolden /tmp/projection-candidate.tsv'
```

This produces project regression data, not an independent oracle. Compare the candidate with the
original and retain the reference-backed reason before any reviewed fixture replacement.

The J2.7/J2.9 history is described in
[ProjectionGolden](../../node/src/test/scala/xyz/kd5ujc/scarab/node/read/ProjectionGolden.scala).
These are project-generated state projections, not captured Haskell query answers.

## #135: genuine pending reward state

On 2026-10-19, 19 rows changed: one each in Shelley, Allegra and Mary, and eight each in Alonzo
and Babbage. The unchanged `EraRead.rewardsDue` contract exposes only a completed update. An
actual native pending update therefore projects `rewardUpdate: null` while work remains; eager
calculation previously exposed the completed value from the start call. All other JSON fields,
including anchors, protocol state, UTxO, accounts, pots and snapshots, are unchanged.

A private field-level proof completed only the **already frozen pending input**, outside the
production read path, and restored every old Shelley-based digest. It directly compared all JSON
fields except `rewardUpdate` and required every changed direct projection to contain `null`. The
maintained test compares the direct native projection; it has no completion adapter or mask.
A separate assertion pins all 19 native pending states and their absent completed-reward view.
Completed reward accounting is independently checked by the
[unchanged Haskell API corpus](../../storage/src/test/resources/rewards-1.19.0.1/README.md).

- Original fixture at `0b5dde8f:fixtures/projection-golden/eras.tsv`: SHA-256
  `2517167e46bd6bf3f30d4c8730981dc6a88c9bec1e7e73d13707b9233b4a2632`.
- Updated fixture SHA-256: `6c3e409a85bfaf66d7e2fb8a2c40353ad1a10abf2e901dd3e9953680ad73d3a6`.
- Proof source SHA-256: `4f515139cc098d87e5c6e3bfa10dcf7a63d2bb71816f621531e441efe25955be`.
- Proof log SHA-256: `6c97aacfcd5ccd2a92593f77bdb096ce91c974d51b34d17bd74719d7852b13eb`.

The source, exact Docker command, classpath, source/class inventories, original/new fixtures and
manifest remain outside Git at `/tmp/scarab/issue-135/RewardGoldenProof.scala` and
`golden-proof-manifest.json`. The proof used frozen runtime `c8265de3` on main `0b5dde8f`.
