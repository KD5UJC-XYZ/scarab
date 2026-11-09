<!-- SPDX-License-Identifier: Apache-2.0 -->
# Add a reference build, or promote a Leios build to stable

**Status: Current.** The value of `--reference-build` names the build of `cardano-node` that the
network runs and that Scarab mirrors ([#826](https://github.com/KD5UJC-XYZ/scarab/issues/826)): the
layout of the reference's database that Scarab reads and writes, which snapshot V10 records with the
state bytes, and, from #826 step c, the ledger and consensus rules. One binary of the reference is
one layout and one rule set, and the value is the binary. A value other than the network's build is
like running another binary: the node would follow other rules than its peers. Operators are trusted
to name the right build; the node logs it at start and warns where a mistake is visible.

The level is 3 ([CONTRIBUTING](../../CONTRIBUTING.md#levels)): a build selects a persisted layout
and, later, rules.

## How a build is named

- **One value per minor release line, `<major>.<minor>`.** The patches of a line share its value,
  because the reference upgrades them in place without a replay. The value behaves as Scarab's
  pinned release of that line behaves; the pin is in
  [`docs/data/reference-pins.json`](../data/reference-pins.json), and the node logs the exact
  pinned release at start (`referenceBuild`).
- **A patch-level value only when the reference's release notes require a replay or a database
  conversion at that patch.** A major line is too broad: cardano-node 10.7.0's release notes require
  a full chain replay inside 10.x, because `TablesCodecVersion1` changed the on-disk serialisation
  of `LedgerState` and `ExtLedgerState`.
- **A Leios build is named by its node commit, `leios-<first 8 hex digits>`.** It is unstable: a
  prototype that can change or go away at any time, meant for use with
  `ExperimentalHardForksEnabled`; the node warns when it runs without it. Its docs say it is
  unstable.
- **Several values may share one layout.** A value is what the operator runs; a layout
  (`StateLayout`) is what the bytes are. A snapshot is read when its recorded build's layout is the
  configured build's; there is no conversion between layouts.
- **A value is never renamed or removed without the owner's decision.** The name is what V10
  snapshots record: a renamed or removed name makes those files unknown, and they are skipped.

### Known differences inside 11.1

The `11.1` value behaves as the pinned release, 11.1.3. Read from the release notes of 11.1.0 to
11.1.3 (their dependency tables) and the sources they name:

- **IPv4 byte order.** 11.1.0 to 11.1.2 use cardano-ledger-binary 1.9.0.0 (cardano-ledger
  f649f9751074d2ab3de033fc3912f29c9862c1f5), which writes and reads an IPv4 address as a
  little-endian 32-bit word (`putWord32le` and `getWord32le` in `Binary/Encoding/Encoder.hs` and
  `Binary/Decoding/Decoder.hs`). 11.1.3 uses the cardano-ledger-binary of cardano-ledger
  048b78d2807ce9644dbe483c605625824f6be88f (released as 1.9.1.0; the release notes call it
  1.9.0.1), which uses network order (`putWord32be`, `getWord32be`). The two swaps cancel for an
  address that came from chain bytes. Only a value built from outside CBOR, such as a
  genesis-staked pool's relay read from JSON, gets different stored bytes, and every IPv4 relay of
  such a database is read as a different address. Scarab reads IPv4 in network order
  (`PoolRelays.scala`), so it reads an 11.1.2 database as 11.1.3 would.
- **`GetGenesisConfig`.** 11.1.0 uses ouroboros-consensus 4.1.0.0; 11.1.1 and later use 4.2.1.0
  (82ecba329d7d054340bf707d44fe6e9ac27cec40), whose node-to-client reply to `GetGenesisConfig`
  went back from 16 fields to 15 (its `CHANGELOG.md`, 4.2.1.0). Scarab answers with the 15 fields,
as 4.2.1.0 does (`ntc/GenesisConfig.scala`).

## Every place to update

1. **The value.** A case of `ReferenceBuild` in
   [`ReferenceBuild.scala`](../../protocol/src/main/scala/xyz/kd5ujc/scarab/protocol/ReferenceBuild.scala):
   its name, its stability (`Stable` or `Unstable`) and its pins (node release tag or commit,
   cardano-ledger, cardano-ledger-binary and ouroboros-consensus commits), with a Scaladoc that cites
   them. `ReferenceBuild.Default` moves only with the owner's decision.
2. **The pins.** Each revision in `docs/data/reference-pins.json`, with `ReferenceBuild.scala` among
   its dependents; `ReferenceBuildSuite` checks that every pin of every value is registered there.
3. **The codecs.** `StateLayout.of` in
   [`StateLayout.scala`](../../protocol/src/main/scala/xyz/kd5ujc/scarab/protocol/StateLayout.scala)
   maps the value to a layout: an existing one if the build writes the same bytes (diff the encoders
   and decoders of every type of the `state` file at the build's pins against the existing layout's,
   era by era, not only the release notes, and check them on a `state` file the build wrote), or a
   new `StateLayout` case. A new layout needs:
   - for each record it writes differently, a case in that record's layout part (the closed enums
     that extend `LayoutPart`: `Bound.Layout`, `ShelleyLedgerState.Layout`, `ExtLedgerState.Layout`,
     `NewEpochState.Layout`, `SnapShots.Layout`, `PoolBlsKey`), with the record's codecs in
     `storage/.../haskell/state` branching on `stateLayout` and refusing to write another layout's
     part;
   - an object of the codecs (`LedgerFileCodecs` with its `stateLayout`, as `HaskellStateCodecs` and
     `LeiosStateCodecs` in `HaskellStateCodecs.scala`) and its case in `HaskellLedgerFile.codecs`;
   - round trips on `state` files of that build, and the refusal of them by every other layout.

   The snapshot format does not change: V10 records the name as text.
4. **The rules.** From #826 step c, the value also selects the ledger and consensus rules; map it
   there as for the codecs. Until then `ReferenceBuildFlag.follows` refuses it at start with
   `ReferenceBuildNotYet.Rules`, while `import-ledger-snapshot` and `export-ledger-snapshot` read
   and write its layout; the rules this build runs produce values of the 11.1 layout.
5. **The help text.** `ReferenceBuildFlag.Help` lists the values from the enum; check the usage lines
   of `follow`, `import-ledger-snapshot` and `export-ledger-snapshot` read correctly.
6. **The docs and the register.** The `--reference-build` row of
   [flags.md](../running/flags.md); [snapshots.md](../running/snapshots.md) where it names the
   values; [leios.md](../leios.md) for a Leios build; the "as built" section of
   [haskell-snapshots.md](../design/haskell-snapshots.md); a row in `docs/data/compatibility.json` and
   its page under `docs/compatibility/`, rendered with `python3 scripts/render_compatibility.py`; a
   changelog fragment in `changes/`.
7. **The tests.** `ReferenceBuildSuite` (the list of names, the pins, the stability and the warning);
   the header golden of `CodecSuite` for the new name's bytes; `SnapshotV10MigrationSuite` for a
   snapshot recorded under another layout; the log golden (`fixtures/log/golden.jsonl`, one
   `referenceBuild` line per value, `LogGoldenSuite`); and, for a new layout, round trips on
   reference bytes of that build.

## Promote a Leios build to stable

When a Leios build becomes a release of `cardano-node`:

1. Add the release line's value (`<major>.<minor>`) with the release tag and its pinned revisions,
   as above. Check its layout against the Leios value's at the release's pins: the same layout maps
   to the same `StateLayout`, so snapshots recorded under the Leios value stay readable.
2. Keep the Leios value while databases recorded under it may exist (a value is never renamed or
   removed without the owner's decision, above), or ask the owner to retire it.
3. Update every doc that calls the build unstable, and move `ReferenceBuild.Default` only with the
   owner's decision.
