# The reference node's chain-dependent state across the eras (DebugChainDepState)

Answers of the Haskell `cardano-node` 11.1.3 to the node-to-client query `DebugChainDepState`
(`GetChainDepState`, what `cardano-cli query protocol-state` prints) at chosen tips of an era-walking
chain, together with the chain that produced them. Evidence for issue #90: in the TPraos eras (Shelley
to Alonzo) the reference answers the TPraos chain-dependent state, not the 8-field Praos state, and the
state translated at the first Babbage block is the one place where the two shapes meet.

Generated 2026-10-16 from a throwaway 3-pool `up-eras` cluster (default parameters, as
[../eras](../eras/README.md)); the chain is a different run from `fixtures/eras`, whose tests do not use
these files.

```sh
python3 scripts/devnet.py --name NAME up-eras &                     # walks Byron to Conway (about 9 minutes)
python3 scripts/devnet.py --name NAME sample-chain-dep --out points.json                  # next to it
python3 scripts/devnet.py --name NAME sample-chain-dep --out fast.json --interval 0.2 \
    --until-epoch 7 --spacing 100000                                # from epoch 5, to catch the epoch 6 boundary
python3 scripts/devnet.py --name NAME capture-eras --out fixtures/chain-dep --conway-blocks 50
python3 scripts/devnet.py assemble-chain-dep --points merged.json   # points.json and fast.json, merged by hash
python3 scripts/devnet.py --name NAME down
```

## Files

| File | Contents |
|---|---|
| `answers.json` | 43 points, oldest first: `{era, epoch, slot, hash, wallSlot, query, answer}`. `era` is the era of the tip's ledger state (`GetCurrentEra` of the same acquire), `epoch` the epoch of the tip block, `slot` and `hash` the tip (`GetChainPoint`, equal to `GetLedgerTip`), `wallSlot` the slot the clock was in. `query` is the CBOR hex of the query as sent (`[0, [0, [era index, [13]]]]`: `BlockQuery`, `QueryIfCurrent`, `DebugChainDepState`) and `answer` the CBOR hex of the item after the `[4, ...]` of `MsgResult`, in the form of `fixtures/devnet/lsq/golden.json`, so it still carries the `QueryIfCurrent` result list `[state]`. |
| `blocks.hex` | 789 raw `[era tag, block]` BlockFetch blocks from Origin (49 Byron, 100 Shelley, 100 Allegra, 96 Mary, 198 Alonzo, 196 Babbage, 50 Conway), one hex line each. The block hash of every point (Blake2b-256 of the header bytes) is in it; `assemble-chain-dep` drops a point whose block is not (rolled back, or past the end of the capture: three Conway points at slots 3879, 3985 and 4056 were dropped, no point was rolled back). |
| `eras.json`, `genesis/` | The manifest and the genesis and configuration files of this run, as in `fixtures/eras` (public data only: key hashes, no signing keys). |

## Cluster parameters and eras

Network magic 1082026, k = 5, f = 0.2, Byron epoch 0 of 50 slots of 1 s, then epochs of 500 slots of
0.1 s, one epoch per step (Alonzo, Babbage two), update proposals signed by the genesis delegates.

| Era | First epoch | First block slot | Protocol |
|---|---|---|---|
| Byron | 0 | 1 | 0 |
| Shelley | 1 | 50 | 2 |
| Allegra | 2 | 550 | 3 |
| Mary | 3 | 1051 | 4 |
| Alonzo | 4-5 | 1560 | 5, 6 |
| Babbage | 6-7 | 2554 | 7, 8 |
| Conway | 8 | 3553 | 9 |

## How the points were taken

`sample-chain-dep` (scripts/devnet/chaindep.py) opens a LocalStateQuery session on node 1 about every
0.8 s, acquires the volatile tip once and asks `GetCurrentEra`, `GetChainPoint`, `GetLedgerTip` and
`DebugChainDepState` (wrapped in the era's `QueryIfCurrent`) in that session, so tip and answer belong
together. It keeps the first tip of every era and epoch, one tip per 100 slots inside an epoch, and
(`--interval 0.2` from epoch 5) tips taken while the clock is already in the next epoch but the tip is
not. Those give the points before the first block of a new epoch or era is applied: the state is then still
the last block's, in the previous era (Mary tip at slot 1549 with the clock at 1553, Alonzo tip at slot
2549 with the clock at 2551, Babbage tip at slot 3544 with the clock at 3551).

## What the answers look like

Shelley to Alonzo (`TPraosState`, TPraos.hs `instance Serialise TPraosState`): `[result]`, then
`[1, [withOrigin lastSlot, ChainDepState]]` (version 1; `[slot]` is `At slot`), with
`ChainDepState = [PrtclState, TicknState, labNonce]`, `PrtclState = [opcert counters, evolving nonce,
candidate nonce]` and `TicknState = [epoch nonce, last epoch block nonce]` (cardano-protocol-tpraos
`API.hs`, `Rules/Prtcl.hs`, `Rules/Tickn.hs`). From Babbage (`PraosState`, Praos.hs): `[0, [lastSlot,
opcert counters, evolving, candidate, epoch nonce, previous epoch nonce, lab nonce, last epoch block
nonce]]`. The first Babbage state (slot 2554) has the previous epoch nonce of the Alonzo epoch nonce
(the nonce of the Alonzo tip at slot 2549), as Praos.hs `translateChainDepState` and the epoch tick
give it.

## All points

| Era | Epoch | Tip slot | Clock slot | Answer bytes | Answer starts with |
|---|---|---|---|---|---|
| shelley | 1 | 50 | 53 | 188 | `8182018281183283` |
| shelley | 1 | 155 | 157 | 250 | `8182018281189b83` |
| shelley | 1 | 260 | 261 | 251 | `8182018281190104` |
| shelley | 1 | 365 | 366 | 251 | `818201828119016d` |
| shelley | 1 | 470 | 470 | 251 | `81820182811901d6` |
| allegra | 2 | 550 | 550 | 285 | `8182018281190226` |
| allegra | 2 | 655 | 655 | 285 | `818201828119028f` |
| allegra | 2 | 755 | 758 | 285 | `81820182811902f3` |
| allegra | 2 | 855 | 855 | 285 | `8182018281190357` |
| allegra | 2 | 955 | 960 | 285 | `81820182811903bb` |
| mary | 3 | 1055 | 1056 | 285 | `818201828119041f` |
| mary | 3 | 1159 | 1160 | 285 | `8182018281190487` |
| mary | 3 | 1264 | 1264 | 285 | `81820182811904f0` |
| mary | 3 | 1366 | 1368 | 285 | `8182018281190556` |
| mary | 3 | 1475 | 1480 | 285 | `81820182811905c3` |
| mary | 3 | 1549 | 1553 | 285 | `818201828119060d` |
| alonzo | 4 | 1560 | 1561 | 285 | `8182018281190618` |
| alonzo | 4 | 1663 | 1666 | 285 | `818201828119067f` |
| alonzo | 4 | 1776 | 1778 | 285 | `81820182811906f0` |
| alonzo | 4 | 1878 | 1883 | 285 | `8182018281190756` |
| alonzo | 4 | 1978 | 1979 | 285 | `81820182811907ba` |
| alonzo | 5 | 2050 | 2050 | 285 | `8182018281190802` |
| alonzo | 5 | 2092 | 2094 | 285 | `818201828119082c` |
| alonzo | 5 | 2153 | 2155 | 285 | `8182018281190869` |
| alonzo | 5 | 2258 | 2260 | 285 | `81820182811908d2` |
| alonzo | 5 | 2359 | 2363 | 285 | `8182018281190937` |
| alonzo | 5 | 2468 | 2468 | 285 | `81820182811909a4` |
| alonzo | 5 | 2549 | 2551 | 285 | `81820182811909f5` |
| babbage | 6 | 2554 | 2556 | 319 | `8182008882011909` |
| babbage | 6 | 2657 | 2660 | 319 | `818200888201190a` |
| babbage | 6 | 2764 | 2765 | 319 | `818200888201190a` |
| babbage | 6 | 2865 | 2869 | 319 | `818200888201190b` |
| babbage | 6 | 2966 | 2973 | 319 | `818200888201190b` |
| babbage | 6 | 3043 | 3051 | 319 | `818200888201190b` |
| babbage | 7 | 3054 | 3055 | 319 | `818200888201190b` |
| babbage | 7 | 3157 | 3158 | 319 | `818200888201190c` |
| babbage | 7 | 3262 | 3263 | 319 | `818200888201190c` |
| babbage | 7 | 3367 | 3367 | 319 | `818200888201190d` |
| babbage | 7 | 3477 | 3479 | 319 | `818200888201190d` |
| babbage | 7 | 3544 | 3551 | 319 | `818200888201190d` |
| conway | 8 | 3557 | 3559 | 319 | `818200888201190d` |
| conway | 8 | 3661 | 3664 | 319 | `818200888201190e` |
| conway | 8 | 3779 | 3784 | 319 | `818200888201190e` |

Provenance and licence: generated by this project from a throwaway local cluster of the official
IntersectMBO `cardano-node` 11.1.3 binaries (Apache-2.0, not redistributed); the chain, keys and genesis
files exist only for that run and contain no private data. The repository's Apache-2.0 applies.
