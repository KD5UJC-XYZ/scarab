<!-- SPDX-License-Identifier: Apache-2.0 -->
# Native Leios committees

Captured on 2026-11-14 from the pinned Leios ledger image by [the harness](../../../scripts/oracle/leios-committee/Main.hs).
The image ID, the ledger and cardano-base revisions, and the digests of the harness, the cases and
the four source files the result depends on are in [provenance.json](provenance.json). The runner
refuses an image whose copies of those files differ from the pinned upstream files.

Each case is a Leios-layout `SnapShots` (cardano-ledger 9df9f98c `State/SnapShots.hs`). The unchanged
decoder rebuilds the mark snapshot with `mkMarkSnapShot`, which memoizes its committee
(`selectLeiosCommittee`, `mkLeiosCommittee` of cardano-base c1e1ed8d). The unchanged
`mkSetSnapShot` then rotates the mark into the set position. The corpus records the acceptance
verdict, the encoded `SetSnapShot` and its committee.

The cases cover:

- committee sizes of zero, below, above and at the `Word16` limit, and above it (refused);
- a key age at the `Word32` limit and above it (refused);
- seats ordered by unsigned stake (2^63 - 1, 2^63, 2^64 - 1), and ties broken by pool id across the
  `7f`/`80` byte boundary, including a tie group that the committee size cuts;
- pools with zero stake or no delegators, which are still seated;
- absent keys, a proof belonging to another key, and an undecodable key (refused by the decoder);
- the last honoured and first aged-out epoch, an age of zero, and a registration after the epoch;
- a key-age sum that wraps past 2^64 - 1, and a mark epoch of 2^64 - 1, whose next epoch wraps to 0;
- stored ratios that are unreduced, zero and above one; the weights are written as the decoder
  reduced them.

These are synthetic API cases, not lawful chain histories.

`LeiosCommitteeReferenceSuite` decodes each case with Scarab's Leios codecs. It derives the
committee with `LeiosCommitteeSelection.ofMark` and compares every acceptance verdict and every
accepted byte of the committee and the set snapshot. Native decoder messages are kept for
diagnosis; they are not compared with Scala's typed errors. No fields or cases are excluded.

The corpus does not exercise SNAP or `maxKeyAgeEpochs` (the key age derived from the KES
parameters). It also does not cover genesis, epoch transitions or a running Leios node; those
remain under #826. The image uses GHC 9.10.3 and the source packages pinned by its recipe, not the
official node executable. Raw build logs are not published.

Regenerate or replay with [the runner](../../../scripts/oracle/leios-committee/run.py).
