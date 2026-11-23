<!-- SPDX-License-Identifier: Apache-2.0 -->
# Reference builds as types

**Status**: Accepted, 2026-11-15: the whole design, in stages, with layouts as types (stage 2), worked
alongside the era typing ([#85](https://github.com/KD5UJC-XYZ/scarab/issues/85)). Three refinements are
added for the owner's review with this document: one class per module layer (§2.3), stage 1's members
and the suites of its differential (§3). Stage 1 not started.
Tracked in [#1063](https://github.com/KD5UJC-XYZ/scarab/issues/1063). **No persisted format changes in this
programme** (§4).

Pins: cardano-ledger f649f975 for the `11.1` build and cardano-ledger 9df9f98c for the `leios-rc-2026w47`
build ([`docs/data/reference-pins.json`](../data/reference-pins.json) has every pin of each build).
Abbreviated reference paths are under `eras/<era>/impl/src/Cardano/Ledger/<Era>/`. Scarab code is named by
its symbols, not its lines.

## 1. The problem

`ReferenceBuild` (`protocol`) is a run-time enum with three cases: `Release11_1` (`11.1`, the default),
`Release11_2` (`11.2`, whose layout is not read or written yet) and `Leios` (`leios-rc-2026w47`). Its value
reaches the code in four ways.

- **Two rules match on the build.** `UtxoTests.injectedBootAddrAttrsTooBig` refuses an oversized bootstrap
  address attribute as `OutputTooBig` under `11.1` and as `OutputBootAddrAttrsTooBig` under the Leios build
  (#832). The Shelley-based UTXO paths only pass the build through to it: `UtxoTests.shelleyAfterPpup`, and
  `TxEnv.referenceBuild` for the later eras. `HardFork.populateVRFKeyHashes`, which Conway's EPOCH reaches
  through `LedgerGlobals.referenceBuild` at a change of protocol version whose major is 11, adds to the map
  of VRF key hashes it has under `11.1` (`HardFork.hs:107-125`) and recomputes it from nothing under `11.2`
  and the Leios build (`State/CertState.hs`). The admission of a ledger state that a snapshot or an import
  brings in matches on it too (`StateAdmission.admit`; what it refuses, and under which build, is in
  [snapshots.md](../running/snapshots.md#unreachable-ledger-state)). `LedgerGlobals.referenceBuild` and
  `TxEnv.referenceBuild` both default to `ReferenceBuild.Default`, so a caller that names no build gets
  `11.1`.
- **Most differences match on the state's layout.** `StateLayout.of(build)` gives `Node11_1` or `Leios`.
  Six enums extend `LayoutPart`, each with a case per layout holding that layout's fields:
  - `SnapShots.Layout` and `PoolBlsKey` (both in `SnapShots.scala`; `PoolBlsKey` sits in `StakePoolState`
    and `StakePoolSnapShot`);
  - `NewEpochState.Layout` (in `EpochState.scala`);
  - `ShelleyLedgerState.Layout`;
  - `Bound.Layout` (in `Telescope.scala`);
  - `ExtLedgerState.Layout` (in `CardanoLedgerState.scala`).

  `PoolParamsBlsKey`, the genesis pool parameters' key by layout, is not a `LayoutPart`. The storage
  codecs match on these parts, and so do these rules:
  - SNAP's rotation (`Snap`);
  - NEWEPOCH without `nesPd` (`NewEpoch.boundaryLayout`);
  - POOLREAP's and POOL's voting key (`PoolBlsKey.certified`, from `PoolReap` and `Certs`);
  - the genesis state (`GenesisStart`, `GenesisPoolFields`, `GenesisMirror`);
  - the hand-over and header rules (`Handover.toShelley`, from `MirrorRules` and `NodeHeaderRules`).
- **Dijkstra's decoders read the D-Leios form whatever the build.**
  - Scarab: `Block.decode` reads a Dijkstra block as a `LeiosBlock`, `Header.fromRaw` reads its header as a
    `LeiosHeader`, and a submitted Dijkstra transaction is read by `DijkstraTx.submitted`.
  - The flat Dijkstra types of the package `xyz.kd5ujc.scarab.ledger.dijkstra` are those of
    cardano-ledger 9df9f98c, and so are the era types' Dijkstra cases that #1043 proposes.
  - Tracked as #54's "Select Dijkstra by reference build" item.
- **The two pins memoize native sub-scripts differently.**
  - At 9df9f98c a decoded native script's sub-scripts keep no bytes (`decodeNoBytesTimelock`,
    `Allegra/Scripts.hs:251-258, 290-292`; `decodeNoBytesMultiSig`, `Shelley/Scripts.hs:278-291`; Dijkstra's
    `decodeNoBytesDijkstraNativeScript`, `Dijkstra/Scripts.hs:321-325`). Each sub-script is memoized with
    empty bytes, so its original bytes are empty, its hash is that of tag 0 alone, and any two decoded
    sub-scripts compare equal.
  - At f649f975 they keep their own bytes (`Allegra/Scripts.hs:240-242`), as the era types' `NativeScriptRep`
    does. A probe of the pinned Leios image confirmed the 9df9f98c behaviour on 2026-11-15.
  - No rule hashes or compares a sub-script, so nothing observable differs yet. The `11.2` re-pin (#1002)
    has to check which form its pin has.

A match is exhaustive, so a new enum case is caught wherever a match names every case. But three problems
remain:
- nothing lists in one place the differences a build must answer;
- the differences are spread over two dozen files;
- a function that takes the build value can be called with any build, including one whose layout the
  state it holds is not in.

#826 step c lists the remaining per-build differences across ledger, consensus, mempool, Plutus,
node-to-client and node-to-node, with #832 and #833 as defaults. Each one that lands adds another match.
#1002 adds an `11.2` build beside `11.1`.

## 2. The design

The build becomes a type parameter, and each difference becomes a member of one type class with one
instance per build. This is the pattern of the era typing ([ADR 0007, amendment of
2026-11-14](../adr/0007-era-indexed-state.md#amendment-2026-11-14-era-specific-types-no-inter-era-generics);
[era-specific transaction types](era-typed-transactions.md)).

### 2.1 Builds as types

`sealed trait Build`, with one subtype per build in its companion: `Node11_1`, `Leios_b004fe0c`, and
`Node11_2` when #1002 adds it.

The `--reference-build` text parses to an existential `SomeBuild`: the build's type with its instance. It is
unpacked once at start-up, as an era is unpacked once at the block boundary. Below that point the node's
program is polymorphic in `B`, with `using BuildRules[B]`. The defaulted `referenceBuild` fields go: a
program that needs the build asks for its instance.

### 2.2 One class, every difference a member

`trait BuildRules[B <: Build]` names each difference once:
- the rule switch (the bootstrap attribute check);
- #826 step c's list;
- the form of the Dijkstra decoders;
- the layout (§2.4).

Most members belong to the layout, not the build. A member that differs by layout is defined once per
layout and reached through the build's `Layout`, so builds that share a layout share it.

The instances are given in the class's companion, one per build, with no defaults. A new build's instance
therefore does not compile until it answers every member: the list of what a new build must answer, made
by the compiler.

### 2.3 One class per module layer

The modules are layered: `protocol`, `ledger`, `consensus`, `storage` and `node`, with `network` beside
them. A class can name only the types of its own module and of those below it. The members of §2.2 are
of every layer: the bootstrap attribute check is a ledger rule, the Peras state and the bounds are
consensus state, the codecs are storage's, and the mempool and node-to-client differences are the node's.

So `BuildRules[B]` is one class per layer, each in the lowest module that sees its members' types:
- each layer's class has one instance per build in its companion, with no defaults;
- `Build` is in `protocol`, below all of them, and so is the layout (§2.4).

The node's unpack names each build's type once, where `ReferenceBuild` becomes `SomeBuild`, and summons
there every layer's instance of that build. A new build with no instance at some layer therefore does not
compile. A program asks only for the layers it uses. The class names are chosen in stage 1.

### 2.4 Layouts as types

The layout becomes a type member of the instance (`type Layout <: StateLayout`). Each state record with a
layout part takes the layout as a type parameter, and its layout fields become a match type with one case
per layout. For example, `SnapShots[L]` has its Leios fields only at `L = Leios`.

A codec is a given per layout, so state in one layout cannot be written by another layout's codec. Several
builds may share one layout. #1002's stage 2 plans a layout of its own for `11.2` (a `StateLayout` case with
its codecs); that plan stands unless evidence shows the two builds' state files agree.

### 2.5 With the era types

An era's types are the same in every build, but a class can have a member at one build's pin and not at
another's. Scarab's class carries the union of the builds' members, and the eras' instances give each build's
values. Each such member is a Deviation against the pin that lacks it (decided 2026-11-15, #85;
[decisions register](../decisions.md#reference-fidelity)). For example,
`EraPParams` has `ppMaxPledgeLeverageG` and `ppMinPoolMarginG` at cardano-ledger 9df9f98c
(`libs/cardano-ledger-core/src/Cardano/Ledger/Core/PParams.hs:510-524`) and not at f649f975. A rule that differs by
build asks for both instances: `using EraTxBody[E]` and `BuildRules[B]`. `EraRules[E]`, the complete rule
set per era (stage 2 of #85), is given per era and takes the build's instance for the members that differ.

## 3. Stages

1. **The class and the unpack.**
   - Scope: `Build`, `SomeBuild`, and the classes of §2.3, with the members listed below. They refine
     the first list of #1063, which also named SNAP, NEWEPOCH and POOLREAP; those wait for stage 2.
   - The node parses the build and unpacks it once, and rules take `BuildRules[B]` instead of a
     `ReferenceBuild` value. No layout changes.
   - Members in this stage are the sites that read the build:
     - the bootstrap attribute check;
     - the voting key POOL registers (`Certs`);
     - the genesis state's layout (`GenesisStart`, `GenesisInjections.read`);
     - the hand-over's layout (the callers of `Handover.toShelley`).

     The sites that match on the layout part the state already holds stay as they are until stage 2
     types them: SNAP, NEWEPOCH's boundary layout, POOLREAP, the seeded genesis snapshots and
     `GenesisMirror`. They key on the state they are given, so no build can reach them with the wrong
     one.
   - `ReferenceBuild` stays as the run-time descriptor (name, stability, pins), the one match left being
     its unpack to `SomeBuild`. No given falls back to `11.1`.
   - Differential: identical verdicts before and after for each build.
     - `11.1`: the refusal corpus (`ReferenceRefusalSuite`, `RemainingPredicateReferenceSuite`) and the
       recorded chains (`PreviewReplaySuite`, `PreviewConwayRulesSuite`).
     - The Leios build: its reference suites (`LeiosEpochReferenceSuite`, `LeiosGenesisReferenceSuite`,
       `LeiosCommitteeReferenceSuite`, `LeiosTestnetBlockReferenceSuite`). No run applies blocks under
       that build while `follow` refuses it.
     - Each member also gets a mutation that swaps the two builds' instances.
   - The UTXO paths' build parameter (`UtxoTests.shelleyAfterPpup`, `TxEnv.referenceBuild`) changes only
     in agreement with the era-types work, whose Conway rules rewrite the same paths.
2. **Layouts as types.**
   - One state record at a time, `SnapShots` and `PoolBlsKey` first, being the smallest with Leios fields.
   - Each record's codecs become givens, with the goldens unchanged.
3. **#826 step c's differences** become members, each tested against its build's reference.
4. **`11.2` (#1002)** is the first new build: its instance is the list of what it must answer.

## 4. What does not change

- **No persisted format changes.** Each layout's bytes stay byte-identical while the codecs move behind
  givens. The golden encodings of `CodecSuite` and the captured Haskell states read and written byte for byte guard this, as in every stage of the era types.
- **`--reference-build` keeps its names and parsing,** and `ReferenceBuild.Default` stays `11.1`.
- **One unstable build at a time** (decided 2026-11-15): a new Leios pin replaces the old instance.

## 5. Trade-offs

- **For:**
  - one place lists every difference;
  - a new build fails to compile until it answers all of them;
  - state in one layout cannot meet another layout's codec;
  - the pattern is the era typing's, so one idea covers both.
- **Against:**
  - a type parameter threads through the node from the unpack point down, as the era parameter does;
  - the state records' layout parameter (stage 2) is a large change touching storage, consensus and the
    node;
  - compile times may grow with the extra givens.
- **Alternative not taken:** keep the run-time enum and only gather the rule switches into one
  `BuildRules` value per build (stage 1 without the type parameter). It is cheaper, but it lists the
  differences without making a wrong layout unrepresentable.
