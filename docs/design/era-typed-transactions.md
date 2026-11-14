<!-- SPDX-License-Identifier: Apache-2.0 -->
# Era-specific transaction types

**Status**: Proposal, 2026-11-14. The decisions are those of the
[ADR 0007 amendment of 2026-11-14](../adr/0007-era-indexed-state.md#amendment-2026-11-14-era-specific-types-no-inter-era-generics).
Tracked in [#85](https://github.com/KD5UJC-XYZ/scarab/issues/85); forging in every Shelley-based era
([#544](https://github.com/KD5UJC-XYZ/scarab/issues/544)) builds on it. **No persisted format changes in this
programme** (§3.6).

Pins: cardano-ledger f649f975 for Shelley to Conway, cardano-ledger 9df9f98c (the Leios testnet build) for
Dijkstra, ouroboros-consensus 82ecba329. Abbreviated reference paths are under
`eras/<era>/impl/src/Cardano/Ledger/<Era>/` and `libs/cardano-ledger-core/src/Cardano/Ledger/`.

## 1. Why

The node mirrors the reference's era-indexed ledger state ([ADR 0007](../adr/0007-era-indexed-state.md)),
but its transactions are not indexed by era.

**One record for every era.** `TxBody`, `Witnesses`, `Tx` and `TxOut` (`ledger/.../Tx.scala`,
`ledger/.../Address.scala`) are single records whose fields are optional by era, and `Certificate` is one
union of every era's certificates. Which fields an era may carry is decided at run time:
- the decoders take the era as a value (`TxBody.decode(raw, era)`);
- the rules either test the era (`ShelleyBasedEra.atLeast`) or group five eras behind one evidence value
  (`PreConway[E]`).

The consequences:
- **Dijkstra's rules decode a Dijkstra transaction a second time, as a Conway one**, and check that
  (`rules/DijkstraUtxo.scala`, `DijkstraStAnnTx.of`).
- **A field's era is checked only at run time.** A Shelley-only certificate in a Conway transaction is
  refused by the decoder and again by the CERTS rule (`rules/Certs.scala`, `TxDefect.PreConwayCertificate`),
  but nothing in the types prevents it. Nothing stops a rule from reading a field its era does not have.
- **The candidate-nonce window is chosen by comparing eras** (`HeaderValidation`, `h.era.ordinal >=
  Era.Conway.ordinal`). That kind of choice goes wrong silently when an era is added.

## 2. What the reference does

**Bodies and transactions.** Every era has its own body and transaction type: `TxBody l era` and `Tx l era`
are data families, one newtype per era (`Core.hs:241`, `Core.hs:167`). Both are indexed by a level, `TxLevel`
(`TopTx | SubTx`, `Core/TxLevel.hs:34`):
- most of a body's lenses take a body of any level: inputs, outputs, certificates, withdrawals, the
  validity interval, mint, the treasury (`Core.hs:245-265`);
- fee, collateral, total collateral, collateral return, sub-transactions and starting balance intervals take
  a `TopTx` body only;
- eras up to Conway have only `TopTx`; Dijkstra has both, `SubTx` being its sub-transactions.

**The other families map to shared types parameterised by the era.** `TxWits`, `TxOut` and `TxAuxData`
(`Core.hs:297-318, 467-483, 503-516`), `Value` (`Core.hs:464`) and `TxCert` (`Core/TxCert.hs:58`):

| Family | Shared type | Eras |
|---|---|---|
| `Value` | `Coin` | Shelley, Allegra |
| | `MaryValue` (ADA and multi-assets) | Mary to Dijkstra |
| `TxOut` | `ShelleyTxOut era` | Shelley, Allegra, Mary |
| | `AlonzoTxOut era` | Alonzo |
| | `BabbageTxOut era` | Babbage, Conway, Dijkstra |
| `TxWits` | `ShelleyTxWits era` | Shelley, Allegra, Mary |
| | `AlonzoTxWits era` | Alonzo to Dijkstra |
| `TxAuxData` | `ShelleyTxAuxData` | Shelley |
| | `AllegraTxAuxData era` | Allegra, Mary |
| | `AlonzoTxAuxData era` | Alonzo to Dijkstra |
| `TxCert` | `ShelleyTxCert era` | Shelley to Babbage |
| | `ConwayTxCert era` | Conway |
| | `DijkstraTxCert era` | Dijkstra |
| raw body | `AllegraTxBodyRaw ma` | Allegra (`ma = ()`), Mary (`ma = MultiAsset`) |
| envelope | `ShelleyTx` | Shelley, Allegra, Mary |
| | `AlonzoTx` | Alonzo, Babbage, Conway |
| | `DijkstraTx` (with `DijkstraSubTx`) | Dijkstra |

**Scripts and Plutus purposes differ by era inside these shared types.** Shelley has `MultiSig`, Allegra to
Conway have `Timelock`, and Dijkstra has `DijkstraNativeScript`. The purposes are `AlonzoPlutusPurpose` for
Alonzo and Babbage, `ConwayPlutusPurpose` for Conway and `DijkstraPlutusPurpose` for Dijkstra.

**The class chain.** Capabilities come from classes, each with instances at exactly some eras, in chains:
- `EraTxBody` ⊂ `AllegraEraTxBody` ⊂ `MaryEraTxBody` ⊂ `AlonzoEraTxBody` ⊂ `BabbageEraTxBody` ⊂
  `ConwayEraTxBody` (`Mary/TxBody.hs:70`, `Alonzo/TxBody.hs:127`, `Babbage/TxBody.hs:110`,
  `Conway/TxBody.hs:626-629`);
- off the chain, `ShelleyEraTxBody` has instances for Shelley to Babbage only (`AtMostEra "Babbage"`,
  `Shelley/TxBody.hs:97`), and `ttlTxBodyL` is for Shelley only (`ExactEra ShelleyEra`, `:98`);
- likewise `EraTxWits` ⊂ `AlonzoEraTxWits`; `EraTxOut` ⊂ `AlonzoEraTxOut` ⊂ `BabbageEraTxOut`;
  `EraTxAuxData` ⊂ `AllegraEraTxAuxData` ⊂ `AlonzoEraTxAuxData`; `EraTx` ⊂ `AlonzoEraTx`.

A rule asks for the class it needs.

**Rules are assigned per era** (`EraRule "UTXO" era`), and one implementation often serves several eras.
Mary's UTXO is Allegra's. Babbage and Conway use Shelley's POOL (`Babbage/Era.hs:102`, `Conway/Era.hs:254`);
Dijkstra has its own (`Dijkstra/Era.hs:282` at 9df9f98c).

**Translation between adjacent eras.** For the Alonzo family on, transaction translation goes through
CBOR: the previous era's bytes are decoded with the next era's decoder. It can fail with a `DecoderError`:
Babbage (`Babbage/Translation.hs:61-78`), Conway (`Conway/Translation.hs:89-97`), Dijkstra
(`Dijkstra/Translation.hs:53-62` at 9df9f98c).

**Stored outputs carry no era.** On disk an output has no era tag. Reading the tables decodes each stored
output as the era the ledger state is in (`indexedUnpackM`, ouroboros-consensus 82ecba329
`Cardano/Ledger.hs:170-187`). In memory an output carries its era (`CardanoTxOut`, `:103-110`), and a value
from an earlier era is upgraded when it is read in a later one (`ejectHardForkTxOut`, `:149-162`).

## 3. Design

### 3.1 One nominal type per era, the sharing underneath

Every era has its own type for each family. Where the reference shares a type, the shared part is a
representation parameterised by what differs, not by the era, and each era's type is an opaque type over it:

| Representation | Behind |
|---|---|
| `ShelleyTxOutRep[V]` | `ShelleyTxOut`, `AllegraTxOut` (`V = AdaValue`); `MaryTxOut` (`V = MultiAssetValue`) |
| `AlonzoTxOutRep` | `AlonzoTxOut` |
| `BabbageTxOutRep[S]` | `BabbageTxOut`, `ConwayTxOut` (`S = Timelock`); `DijkstraTxOut` (`S = DijkstraNativeScript`) |
| `ShelleyTxWitsRep[S]` | the witness sets of Shelley (`S = MultiSig`), Allegra and Mary (`S = Timelock`) |
| `AlonzoTxWitsRep[S]` | the witness sets of Alonzo to Conway (`S = Timelock`), Dijkstra (`S = DijkstraNativeScript`) |
| `AllegraTxAuxDataRep` | the auxiliary data of Allegra and Mary |
| `AlonzoTxAuxDataRep[S]` | the auxiliary data of Alonzo to Conway (`S = Timelock`), Dijkstra (`S = DijkstraNativeScript`) |
| `ShelleyTxCertRep` | the certificates of Shelley to Babbage |
| `AllegraTxBodyRep[M]` | `AllegraTxBody` (`M = Unit`), `MaryTxBody` (`M = MultiAsset`) |

Bodies and certificates that the reference does not share are plain case classes of their era: Shelley's,
Alonzo's to Dijkstra's bodies, and Conway's and Dijkstra's certificates. Purposes follow the same rule. Each
representation keeps the bytes it was decoded from. The output representations also keep the value's encoded
size and the reference-script re-read that the size rules and the reference-script fee use today.

**Where the opaqueness ends.** A top-level opaque type is transparent only in the file that defines it. Each
era therefore has one file of its types (`conway/ConwayTypes.scala`). That file defines the opaque types and
their companions. The constructors are private members of the companions, and the companions hold the
instances, the decoder entry points and the translation in from the previous era. Other code sees only the
era types. A public `rep` gives read-only access to the shared fields.

**Controlled construction.** A value of an era is made only by:
- that era's decoder;
- its translation from the previous era (§3.7);
- the projection of a stored output (§3.6), which is partial and `private[ledger]`. Its callers are the
  rules' reads of the UTxO set, in `ledger`.

**Equality.** The `era` packages use strict equality: `CanEqual` is given per era type, so `babbageOut ==
conwayOut` does not compile. `Eq` and `Show` derive on the representation and are forwarded to each era type
by one helper.

### 3.2 Families as match types, capabilities as classes

- **Families.** `TxBody[L <: TxLevel, E]`, `Tx[L, E]`, `TxOut[E]`, `TxWits[E]`, `TxAuxData[E]`, `TxCert[E]`,
  `Script[E]`, `PlutusPurpose[E]` and `Value[E]` are match types with one case per era, as `CertState[E]` is.
  Eras before Dijkstra reduce only at the `Top` level.
- **Classes.** Type classes over the era tag, named after the reference's and chained as its are (§2), with
  the reference's methods:
  - a method that takes a body of any level is generic in `L`;
  - fee and collateral take a `Top` body;
  - `EraTx[E]` aggregates the classes an era's rules need, from the reference's superclass constraints, so
    a rule asks for one or two classes.
- **Instances.** Each class family has one `given` per era, in the root class's companion (`EraTxBody`),
  typed as that era's instance trait (`ConwayTxBodyOps extends ConwayEraTxBody[Conway]`). One given per era
  means no ambiguity, and the root companion lists the eras in one place.
- **State classes.** Removing `PreConway` removes its state equalities (`CertState`, `GovState`,
  `InstantStake` and their lenses), so the reference's state classes come with the transaction classes:
  `EraCertState`, `ShelleyEraAccounts`/`ConwayEraAccounts`, `EraGov`, `EraStake`, `EraPParams`,
  `ShelleyEraTxCert`/`ConwayEraTxCert`.
- **Plutus.** The script contexts take the per-era `TxInfo` construction of the reference's
  `EraPlutusTxInfo`.
- **The witness.** `ShelleyBasedEra[E]` only unpacks an era known at run time and routes to that era's code
  with an exhaustive match. It is never compared.

### 3.3 Levels

`TxLevel` (`Top`, `Sub`) indexes every era's body and transaction, as the reference's does. Dijkstra's
sub-transactions are `TxBody[Sub, Dijkstra]`, so the shared rules that Dijkstra's sub-transaction rules reuse
(Conway's CERTS and GOV) run on them unchanged. This replaces `DijkstraTxBody`'s run-time `level` and its
`fee: Option`.

### 3.4 Rule sets

- **Shared rules.** A rule the reference shares is written once and asks for the reference's class.
- **A complete set per era.** Each era has a complete rule set, `EraRules[E]`: one member per rule, none
  defaulted, each naming the shared or era-specific rule that era runs, as the reference's `EraRule` table
  does. A test checks every era's set against the reference's table (§2: Dijkstra's POOL is its own).
- **Failure types** get per-era names over shared representations in the same way.

### 3.5 Decoders

Each era has its own decoder entry point (`ConwayTxBody.decode(raw)`). At first it projects from today's
decoder; each stage then moves the field reading into the era's decoder. Acceptance must not change. The
differential compares the old and new decoders on:
- the recorded blocks;
- the reference refusal corpus;
- the decoder fuzz targets.

It compares typed results, including the `LedgerDecodeError` of a refusal, not only success.

### 3.6 The store, unchanged

The persisted form stays exactly as it is: the store keeps no era, as the reference's tables keep none (§2).
- **What stays.** The case class named `TxOut` with today's fields stays as the storage form, in its package
  and under its name, because the sidecar fingerprint hashes the type's label and field labels
  (`storage/.../Codec.scala`). The rules stop using it.
- **The readers that must stay byte-identical:**
  - the sidecar codecs (`NodeCodecs`, `SnapshotSidecar`, and the V7 and V8 readers that import the live
    `TxOut` codec);
  - `UtxoStore`'s value encoding and its `utxo.bin` checkpoint;
  - `FrozenProtocolParams`;
  - the Haskell-state output codecs (`haskell/state/UtxoCodecs.scala`, already routed by era);
  - `mempack/TxOutMemPack`, which `haskell/state/SnapshotTableStream` also reaches.

  The storage form keeps its simple name `TxOut`, because the fingerprint uses the type's label. Code that
  also uses the era family imports it under an alias; it is never renamed. Its value stays the flat `Value`,
  not `AdaValue` or `MultiAssetValue`.
- **A stored output is read into the era that reads it**, as `indexedUnpackM` does. Each era's `fromStored` returns an `Either`: it refuses an output of a later era (the reference's `anachrony`) and a shape the era
  cannot hold.
- **The invariant this rests on:** an output whose encoding changes at a crossing is rewritten when the chain
  crosses. Today that is Dijkstra's native reference scripts (`Translate.upgradeUtxo`,
  `UtxoStore.enteredDijkstra`). A property test checks it over the recorded corpus: reading an output in
  era N and translating it to the tip gives the same output as reading it at the tip.

Any change to what is stored needs the owner's approval first.

### 3.7 Crossing an era boundary

- **Translations** between adjacent eras are explicit functions of the later era (Conway's `translate`).
  From Alonzo on they re-decode the previous era's bytes with the next era's decoder, as the reference's
  translation through CBOR does, and they return its typed failure.
- **The mempool** injects an earlier era's transaction through the same chain (`hardForkInjectTxs`,
  ouroboros-consensus 82ecba329 `Cardano/CanHardFork.hs:182-224`, ending at Conway to Dijkstra). It refuses
  a Byron transaction and one of a later era ([#934](https://github.com/KD5UJC-XYZ/scarab/issues/934)).

### 3.8 What stays a run-time check

Era typing does not establish these, so they stay run-time checks:
- predicates over a transaction's contents, the ledger state, time and the parameters in force;
- protocol-version gates inside an era (`hardfork*`);
- the network and the configured reference build;
- matching an era tag read from bytes against the era the chain is in.

## 4. Stages

**How it lands.** Work lands on `feat/era-types` ([#990](https://github.com/KD5UJC-XYZ/scarab/pull/990)), as
several commits or small pull requests into the branch per stage, and reaches `main` once per stage.

**Every stage:**
- compiles and passes `check` and preflight;
- runs the scenarios of the areas it touches;
- **does not change behaviour**: the reference refusal corpus, the upstream conformance suites and an
  old-against-new differential on recorded blocks give identical verdicts and identical failures, except in
  the one stage that changes them on purpose (#412);
- moves its test builders to per-era test kits that build through the encoders and decoders, so a test
  value's bytes stay true.

| Stage | Deliverable |
|---|---|
| 1. Core | `TxLevel`; the match types and the transaction, state and Plutus classes in the reference's chains (each era's match-type cases and instances are added in that era's stage); the value types (`AdaValue`, `MultiAssetValue`); the representations; compile-failure tests (missing capability, another era's value, construction outside the era's file, equality across eras). No change to the rules, to `Utxo` or to any codec. |
| 2. Conway | Conway's types and decoder. Its rules on the classes (LEDGER, UTXO, UTXOW, CERTS, GOV, MEMPOOL), with failures identical. The mempool, local submission, TxSubmission2 relay and the forger typed by Conway. The performance of Conway's UTXO path on the preprod replay is measured here. |
| 3. Dijkstra | Converge on the shared representations, `TxBody[Sub, Dijkstra]`, remove the re-decode as Conway, its translation from Conway. Coordinated with the Leios work before it starts. |
| 4. Babbage and Alonzo | Their types, decoders, translations and rules on the classes, with failures identical. Conway's translation in from Babbage, and the mempool's injection of a Babbage transaction into Conway. |
| 4b. #412 | The accumulation of predicate failures in the reference's STS order, in its own pull request, after the rules are typed. The refusal corpus is regenerated and compared against the reference. |
| 5. Mary, Allegra, Shelley | Their types, decoders, translations and rules. Then remove `PreConway`, the grouped shapes and every era comparison (below). |
| 6a. Remove the flat types | Delete `TxBody`, `Witnesses`, `Tx`, `Certificate` from the rules, fuzz targets and tools, once each is confirmed absent from every persisted codec. The storage form `TxOut` stays (§3.6). |
| 6b. Era-typed headers and consensus parameters | Per-era consensus configuration, with headers indexed by era (§5). |
| 6c. Mempool and submission before Conway | The mempool, local submission, TxSubmission2, the local monitor and the failure encoders for every Shelley-based era. |
| 6d. Forging in every era (#544) | On 6b and 6c, with a devnet gate that starts in Shelley. |

**What stage 5 removes:** every era comparison (`ShelleyBasedEra.atLeast`), wherever it is:
- the rules;
- `ledger`'s `ProtocolParams`, `Block`, `Governance` and `ScriptIntegrity`;
- the storage codecs;
- the node-to-client queries;
- the predicate-failure encoders (the per-era failure CBOR).

A decoder-version comparison (`DecoderVersion.atLeast`), which mirrors the reference's
`ifDecoderVersionAtLeast`, stays. The ledger-state translations of `Translate` already work per era and are
unchanged, apart from reading outputs through §3.6.

**Scenarios by stage:** `e2e` for every stage that touches rules or headers; `pool` and `ntc` for 2 and 6c;
`pool` for 6d; `restart` wherever a storage reader is touched.

## 5. Consensus parameters per era

The window after which the candidate nonce is frozen, per era (ouroboros-consensus 82ecba329 `Cardano/Node.hs`):

| Era | Window | Source |
|---|---|---|
| Shelley, Allegra, Mary, Alonzo | ⌈3k/f⌉ (TPraos) | `tpraosParams`, `:710, 732, 751, 770` |
| Babbage | ⌈3k/f⌉ | the override at `:787-799` |
| Conway | ⌈4k/f⌉ | `praosParams`, `:816-818` |
| Dijkstra | ⌈4k/f⌉ | `praosParams`, `:835-837` |

How this is typed:
- each era has a consensus configuration, a given per era, as the reference's `PartialConsensusConfig`;
- a header and the configuration that checks it share one era parameter;
- the two windows are distinct opaque types that only the epoch geometry builds;
- the nonce update stays one function, as in the reference.

Before the era comparison in `HeaderValidation` is deleted, a test checks that every era's given window
equals what the comparison gives today. A wrong window splits the chain.

## 6. The spike

`feat/era-types` has a spike for Babbage and Conway (`ledger/.../ledger/era/`, `EraTxSpikeSuite`), which
passes `check`.

**What it shows:**
- opaque per-era outputs over one representation, with a cross-era translation and no casts;
- per-era bodies with private constructors, made by a decoder that projects from today's;
- classes whose instances are given in a root companion and resolve for subclass requests;
- the compiler refusing a missing capability, another era's output, and construction outside the defining
  file. Two of these tests check only that compilation fails, not the message.

**What it does not show yet:**
- a representation parameterised by value (Allegra and Mary outputs, arithmetic over `Value[E]` through the
  reference's `Val` class);
- the level parameter;
- a rule reading a stored output through `fromStored`;
- a complete rule set;
- a refusal tested from a sibling file in the same package.

Its decoded bodies are compared on id, fee, output bytes and one era-only field. Stage 1 extends it to cover
these before anything else is built on it.

Its two findings:
- An instance written as an anonymous class whose methods take match-type parameters needs explicit
  `override`s, or `-Wunused` reports the methods as unused.
- `derives` does not apply to an opaque type; instances derive on the representation.

## 7. Risks

- **A split through a wrong per-era choice:**
  - the nonce window (§5);
  - an era's rule set (§3.4);
  - a translation (§3.7).

  Each has its own test against the reference's table before the run-time choice it replaces is removed.
- **A changed failure set:** prevented by the differential gate of every stage (§4), with #412 alone in
  its own pull request.
- **The store:** the storage form and every reader in §3.6 stay byte-identical. The fingerprint goldens in
  `CodecSuite` guard them.
- **Size of the change:** about 45 test files build the flat `TxBody`, about 90 the flat `TxOut` and `Tx`,
  and eight tools use them. The per-era test kits move them stage by stage.
- **Concurrent work:** the Leios work touches the Dijkstra rules. The two efforts agree who goes first
  before stage 3 starts.
