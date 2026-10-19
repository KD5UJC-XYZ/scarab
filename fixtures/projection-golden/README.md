# Era-state projection digests

`eras.tsv` records `index TAB era TAB block number TAB SHA-256` of the direct native JSON
projection for all 731 states in the era-walking capture. The 50 historical Byron rows remain
archived as originally recorded; `ProjectionSuite` checks all 681 Shelley-based rows, while the
Byron consensus-only contract has its own assertion.

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

- Original fixture at `c8265de3:fixtures/projection-golden/eras.tsv`: SHA-256
  `2517167e46bd6bf3f30d4c8730981dc6a88c9bec1e7e73d13707b9233b4a2632`.
- Updated fixture SHA-256: `6c3e409a85bfaf66d7e2fb8a2c40353ad1a10abf2e901dd3e9953680ad73d3a6`.
- Proof source SHA-256: `4f515139cc098d87e5c6e3bfa10dcf7a63d2bb71816f621531e441efe25955be`.
- Proof log SHA-256: `6c97aacfcd5ccd2a92593f77bdb096ce91c974d51b34d17bd74719d7852b13eb`.

The source, exact Docker command, classpath, source/class inventories, original/new fixtures and
manifest remain outside Git at `/tmp/scarab/issue-135/RewardGoldenProof.scala` and
`golden-proof-manifest.json`. The proof used frozen runtime `c8265de3` on main `0b5dde8f`.
