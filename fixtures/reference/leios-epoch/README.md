<!-- SPDX-License-Identifier: Apache-2.0 -->
# Native Leios epoch ticks

Captured on 2026-11-14 from the pinned Leios ledger image by [the harness](../../../scripts/oracle/leios-epoch/Main.hs).
The image ID, the ledger and cardano-base revisions, the digests of the genesis files, the harness,
the cases and the source files the result depends on are in [provenance.json](provenance.json). The
runner refuses an image whose copies of those files differ from the pinned upstream files.

Each case starts from the state Conway's genesis injection gives for the Leios testnet's genesis
files (`fixtures/leios/testnet/config`), with the Shelley genesis's protocol version set to 10, as
in the [genesis corpus](../leios-genesis/README.md). The testnet's pool holds no voting key there
(`"blsKey": null`); a `keyed` case gives it the first devnet pool's key. The state is then changed:
`reregister` gives the pool future parameters that are its own with the cost one lovelace higher
and no voting key, the form a certificate registers up to Conway (parameters are read at the era's
lowest protocol version, Conway's 9, and carry a key from 12 only); `retireAt` retires the pool at
that epoch. These changes are set directly and are not a lawful chain's history. The state, without
its UTxO, is recorded as the case's `input`.

The harness then applies Conway's unchanged `TICK` to each slot, each from the state of the one
before: the first slot of epochs 1, 2 and 3, and the middle of epochs 1 and 2, past the start of the
reward update (the randomness stabilisation window is 8640 slots). That covers NEWEPOCH with the
reward update, EPOCH's SNAP (the Leios layout's mark, set with its committee, and go), POOLREAP's
activation of the future parameters and its retirements, the ratification and enactment of
governance, and RUPD's pulsing reward update. After each tick it records the SHA-256 of the state
without its UTxO (as a ledger snapshot holds it, the UTxO in its tables; the state is ticked without
it as well), and the exact bytes of its snapshots and pool states. The case also records the
globals the tick ran with.

`LeiosEpochReferenceSuite` decodes each input with the Leios layout's codecs, checks it reads back
to its bytes, and ticks it with `Tick` in the node's globals for the testnet's configuration, which
it first checks are those the native tick ran with. After every tick it compares the snapshots, the
pool states and the digest of the state as the Leios writer writes it, which refuses a part of the
11.1 layout. No fields or cases are excluded.

The corpus covers Conway only, on the Leios layout. The testnet's configuration enters Dijkstra at
epoch 0, whose EPOCH the node does not implement yet (#54); this corpus does not cover it, blocks,
transactions, or the consensus layer's parts of the state. The image uses GHC 9.10.3 and the
source packages pinned by its recipe, not the official node executable. Raw build logs are not
published.

Regenerate or replay with [the runner](../../../scripts/oracle/leios-epoch/run.py).
