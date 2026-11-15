<!-- SPDX-License-Identifier: Apache-2.0 -->
# Dijkstra protocol-parameter updates

113 updates measured on 2026-11-15 against the unchanged Haskell ledger of the public Leios testnet build
(cardano-node `b004fe0c`, cardano-ledger `9df9f98c`, cardano-ledger-dijkstra 0.5.0.0), for
[#85](https://github.com/KD5UJC-XYZ/scarab/issues/85). The oracle source and
[recipe](../../../scripts/oracle/dijkstra-pparams/README.md) are committed; `provenance.json` records the
image identity and the program's hash. Raw run artifacts are not published.

`cases.jsonl` holds one case per line:
- `id`: the case's name;
- `update`: the hex of a `PParamsUpdate DijkstraEra` as the reference encodes it from version 12, a map of
  update keys to values;
- `reference`: either the decoder's refusal as `show` renders it (`refused`), or what the build makes of
  the decoded update:
  - `ppuWellFormed` at versions 12.0 and 10.0 (`wellFormed12`, `wellFormed10`;
    `eras/dijkstra/impl/src/Cardano/Ledger/Dijkstra/PParams.hs:1149-1175`);
  - `modifiedPPGroups` (`groups`), each group a DRep group and a stake-pool group.

The cases:
- the empty update;
- every update key alone, with a zero value and a non-zero one;
- every field set, then every field set but a zero `nOpt` or a zero `eMax`;
- two mixed updates;
- an unknown key.

The build refuses four: a zero reference-script cost stride (a `NonZero Word32`), a zero reference-script
cost multiplier and a zero Peras healing factor (each a `PositiveInterval`), and the unknown key. In every
case the verdict is the same at versions 12.0 and 10.0: Dijkstra's well-formedness does not read the version.

`DijkstraPParamsSuite` (ledger) decodes each update with the node's decoder and compares the refusals, the
well-formedness at both versions and the groups. Not measured: applying an update to parameters, and the
parameters' own encoding.
