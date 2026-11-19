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
- most of a body's lenses take a body of any level: inputs, outputs, certificates and withdrawals
  (`Core.hs:245-265`), the validity interval (`Allegra/TxBody.hs:90`), mint, the treasury
  (`Conway/TxBody.hs:632`);
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
| `BabbageTxOutRep[S]` | `BabbageTxOut`, `ConwayTxOut`, `DijkstraTxOut`, `S` the era's script (`BabbageScript`, `ConwayScript`, Dijkstra's), so carrying an output into the next era carries its reference script (`upgradeScript`) |
| `ShelleyTxWitsRep[S]` | the witness sets of Shelley (`S = MultiSig`), Allegra and Mary (`S = Timelock`) |
| `AlonzoTxWitsRep[S]` | the witness sets of Alonzo to Conway (`S = Timelock`), Dijkstra (`S = DijkstraNativeScript`) |
| `AllegraTxAuxDataRep` | the auxiliary data of Allegra and Mary |
| `AlonzoTxAuxDataRep[S]` | the auxiliary data of Alonzo to Conway (`S = Timelock`), Dijkstra (`S = DijkstraNativeScript`) |
| `ShelleyTxCertRep` | the certificates of Shelley to Babbage |
| `NativeScriptRep[F]` | `MultiSig` (`F = NativeForm.MultiSig`), behind Shelley's native script; `Timelock` (`F = NativeForm.Timelock`), behind each era's native script from Allegra to Conway (`AllegraTimelock` to `ConwayTimelock`) |
| `AlonzoScriptRep[N, P]` | the scripts of Alonzo to Conway: the era's native script `N` or its Plutus script `P` (`BabbageScript`, `ConwayScript`) |
| `Plutus[L]` | a Plutus script of language `L`, shared by the eras that have the language; which eras have which language is each era's own Plutus script (`BabbagePlutusScript` has V1 and V2, `ConwayPlutusScript` V1 to V3) |
| `AllegraTxBodyRep[M]` | `AllegraTxBody` (`M = Unit`), `MaryTxBody` (`M = MultiAsset`) |

Bodies and certificates that the reference does not share are plain case classes of their era: Shelley's,
Alonzo's to Dijkstra's bodies, and Conway's and Dijkstra's certificates. Purposes follow the same rule. Each
representation keeps the bytes it was decoded from; a native script keeps them at every level, as the
reference's `MemoBytes` does, each level a window of one copy the script owns. The output representations
also keep the value's encoded size. Their reference script is the era's script, read again from the
output's bytes when a stored output is read into the era, as the flat output's reference-script reads are.

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

Until stage 6a restricts the flat `TxOut`, this holds only by convention inside `ledger`: any code there can
still build a flat output and project it.

**Equality.** `CanEqual` is given per era type, with itself only, so in code compiled under strict equality
(`import scala.language.strictEquality`) `babbageOut == conwayOut` does not compile. The module does not
compile under strict equality, so each era's rule files import it as they move onto the era types (from
stage 2); until then a cross-era comparison outside them compiles. The shared representations have no
`CanEqual`, so under strict equality their values are not compared either. `Eq` and `Show` derive on the representation and are forwarded to each era type by
one helper, from stage 2.

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
  also uses the era family imports it under an alias; it is never renamed. The fingerprint walks every type
  nested in it, so these stay the flat types they are today, unchanged: its `Value` (not `AdaValue` or
  `MultiAssetValue`), `Address`, `Datum` and `Script` (not the per-era `Script[E]`).
- **Other persisted state stays byte-identical too.** That covers the derived protocol-state codecs
  (`NodeCodecs`) and the chain-dependent state in the sidecar. Stage 6b retypes headers and consensus
  configuration, not the persisted chain-dependent state.
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
- **does not change the persisted format**: no fingerprint golden in `CodecSuite` and no maintained snapshot
  fixture changes. A stage that cannot meet this stops, and the change is put to the owner as its own
  question first;
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

**How stage 1 lands on the branch.** A match type needs a case and a class names the families it uses, so a
family and its classes are declared in the commit that brings the first era having them, not ahead of every
era. Stage 1 carries the value, output and script families, classes and representations for the eras the
spike has (Allegra, Mary, Babbage, Conway), and each later stage adds its era's cases and instances. The
other representations of §3.1 (the witness sets, auxiliary data, certificates and Allegra's body) and the
state and Plutus-context classes arrive with their first era, Conway's in stage 2.

**What stage 5 removes:** every era comparison (`ShelleyBasedEra.atLeast`), wherever it is:
- the rules;
- `ledger`'s `ProtocolParams`, `Block`, `Governance` and `ScriptIntegrity`;
- the storage codecs;
- the node-to-client queries;
- the predicate-failure encoders (the per-era failure CBOR).

A decoder-version comparison (`DecoderVersion.atLeast`), which mirrors the reference's
`ifDecoderVersionAtLeast`, stays. The ledger-state translations of `Translate` already work per era and are
unchanged, apart from reading outputs through §3.6.

**The old and new paths side by side.** From stage 2 an era's rules run on its typed transactions, and
the flat path stays alive for the eras not yet typed. Conway's checks on the flat `Tx` stay until stage 3,
because Dijkstra still runs them on its transactions decoded as Conway ones. The differential gate compares
the two paths while both exist. An era's flat path is deleted at the end of the stage that types the last
era using it: Conway's at the end of stage 3, the shared Shelley-to-Babbage code at the end of stage 5.

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

## 6. Results

Stage 1 and Conway's types, the first part of stage 2, are on `feat/era-types`
([#990](https://github.com/KD5UJC-XYZ/scarab/pull/990)) in `ledger/.../ledger/era/`, and pass `check`. They
reach `main` together (decided 2026-11-15, #990): the core with Conway's types, which no rule uses yet.
Conway's rules on the classes follow as the rest of stage 2.

**What it shows:**
- opaque per-era outputs over one representation, with a cross-era translation and no casts;
- per-era bodies with private constructors, made by a decoder that projects from today's;
- classes whose instances are given in a root companion and resolve for subclass requests;
- the compiler refusing a missing capability, another era's output, and construction outside the defining
  file. Two of these tests check only that compilation fails, not the message.

**The level parameter** (stage 1's first task, done):
- Each era's instance names the levels it has, as a type member (`Level[L]`), the reference's `STxLevel`.
  Eras up to Conway use `TopOnly`, whose one case is `Top`; Dijkstra will use `BothLevels`.
- A class method of any level takes the evidence, and an instance matches on it, which shows the compiler the
  level and reduces the body type with no cast.
- Code asking for a sub-transaction body of Conway does not compile.
- Given a concrete body, the caller names the level (`inputs[TxLevel.Top](body)`), because the level cannot
  be inferred back from the body type. Generic rules carry the evidence instead.

**The representation parameterised by value** (stage 1, done):
- `AllegraTxOut` and `MaryTxOut` are opaque over one `ShelleyTxOutRep[V]`, with `V = AdaValue` and
  `MultiAssetValue`.
- A function written once (`coinOf`, in `EraValueSuite`) adds outputs' values up through the reference's
  `Val` in any era. Read from recorded Allegra and Mary blocks, its results equal the flat outputs'. `Val` is
  the reference's signed group (`<+>`, `<->`, `<×>`), so `AdaValue` holds a negative amount
  in a calculation and only an output's decoder bounds it.
- The store's read into Allegra (`fromStored`, `private[ledger]`) refuses an output with native assets, and
  an Allegra output carries into Mary with its bytes and lovelace.
- The compiler refuses:
  - an Allegra output given as a Mary one;
  - a Mary output made from its representation in a sibling file of the same package.

  The complete per-era rule set (`EraRules[E]`, §3.4) comes with Conway's rules; the spike's one-rule set is
  removed.

**The core** (stage 1, for the spike's eras):
- The families `Value`, `TxOut`, `TxBody`, `NativeScript`, `Script` and `PlutusScript` are match types, in
  their own file with the level types. The value is the `Value era` family, not a type member of the output
  class.
- Each era has its own native script, Plutus script and script: Babbage's Plutus script has V1 and V2 and
  Conway's V1 to V3, so a V3 script of Babbage does not compile. Babbage's and Conway's decoders read as the
  flat script decoder does and refuse what it refuses with the same error; a Babbage script carries into
  Conway with its hash. The flat decoder refuses what the reference refuses, but two of its refusals report
  another error than the reference's: a language the era lacks before the script's bytes are read, and tag
  4 as unknown rather than Plutus V4. They are tracked with the other differences in what a refusal reports
  ([#796](https://github.com/KD5UJC-XYZ/scarab/issues/796)), and the era decoders keep the flat decoder's
  until that work changes both.
- A native script keeps the bytes of every level (`NativeScriptRep`), read through the flat decoder and a
  second walk on the heap. It owns one copy of its bytes and each level is a window of it, so a script kept
  from a block does not keep the block alive. The preprod script that nests 5,383 `all` levels reads on a 256 KiB stack, and its hash is the flat
  decoder's.
- The script classes run from `EraScript` to `AlonzoEraScript`, and the output classes from `EraTxOut` to
  `BabbageEraTxOut`. A class whose reference superclass is of another family names that instance as a
  member (`BabbageEraTxOut.scripts`, `EraTxBody.outputsOf`), so each era keeps one given per class family.
- `BabbageTxOutRep[S]` carries the era's script. The store's read into Babbage or Conway reads the reference
  script again from the output's bytes in the era's type, and a stored V3 reference script is refused as a
  Babbage one.
- Each era type has `CanEqual` with itself only. Every compile-failure test has a twin that differs in the
  one thing refused and compiles.
- Not yet: the reference's native-script builders (`mkRequireSignature` and the others) need a
  native-script encoder, which the node does not have. They come with the per-era test kits.

**Conway's types** (stage 2, first part):
- Conway's body has every field of the reference's `ConwayTxBodyRaw`, in its types: sorted sets of inputs and
  required signers, ordered sets of certificates and proposals (`OSet`), nested sorted maps of votes, `Coin`
  amounts, a `Network`, and each output `Sized` by the encoding it was read from. They are read through the
  body classes from `EraTxBody` to `ConwayEraTxBody`, which include Babbage's sized outputs. The classes'
  signatures are the reference's class declarations: the certificates are a sequence in `EraTxBody`
  (`certsTxBodyL`, `Core.hs:265`) while Conway's body keeps them as an `OSet`, and the sized outputs with the
  collateral return are of a body of any level (`allSizedOutputsTxBodyF`, `Babbage/TxBody.hs:121`). Each
  recorded Conway body reads with every field equal to the flat decoder's.
- Conway's certificate is its own type over the shared `PoolCert`, `ConwayDelegCert`, `ConwayGovCert` and
  `Delegatee`. A delegation to neither a pool nor a DRep cannot be written. Each of the 17 Conway forms of a
  certificate reads into its constructor, with the reference's key and script witnesses, and the script
  witness equals the one the flat rules ask for. A pool registration's parameters are equal when their
  relays and metadata mean the same, as the reference's derived `Eq StakePoolParams` compares them, though the
  bytes they were read from are kept. Deposits and refunds are `Coin`. The certificate classes are
  `EraTxCert`, `ShelleyEraTxCert` and `ConwayEraTxCert`, each reader with its builder. A pool is a
  `KeyHash[StakePool]` and a retirement epoch an `EpochNo`, and each credential is of its role
  (`Credential[Staking]`, `[DRepRole]`, `[ColdCommittee]`, `[HotCommittee]`), as in the reference's classes,
  so a staking credential given as a DRep's does not compile.
- Outputs and proposals compare as the reference's derived `Eq` does: an output by its address, value, datum
  and reference script, not the bytes it arrived as; a proposal (`ProposalProcedure`) by what it means, so
  treasury withdrawals compare as the map they are. The reference's genesis-delegation and instantaneous-reward
  readers are on `ShelleyEraTxCert`, with `AtMostEra "Babbage"` on each method (`Shelley/TxCert.hs:164-168`).
  A Scala method could take the same bound as evidence, but that evidence would name a range of eras, which
  the ADR 0007 amendment rules out. So they come with those eras in a class of their own (approved
  2026-11-15, #990; implemented with Babbage's certificates in stage 4), whose
  instances are exactly Shelley's to Babbage's.
- Conway's script class is `ConwayEraScript`, which builds and reads the voting and proposing purposes, and
  `ConwayEraTxBody` names it. `AlonzoEraScript`'s own purpose builders and readers wait for Babbage's
  purposes and certificates (stage 4), as Babbage's script instance is an `AlonzoEraScript`.
- Conway's witness set has the reference's `AlonzoTxWitsRaw` fields: key witnesses as a set under the
  reference's `Ord WitVKey`, bootstrap witnesses, Conway scripts by hash, the datums by hash and the
  redeemers keyed by Conway's own purpose (`ConwayPlutusPurpose`, in the forms `AsIx`, `AsItem` and
  `AsIxItem`), the last two with the bytes they arrived as (`TxDats`, `Redeemers`), which the script
  integrity hash is over, and each datum and redeemer's data decoded with its own bytes (`Data`, the
  reference's `Data era`). The witness
  classes are `EraTxWits` and `AlonzoEraTxWits`. The flat decoder reads the set and refuses what Conway
  refuses. Its scripts are then read again from the same bytes into Conway's types, each native script with
  its own bytes, and they must be the flat decoder's by hash. Every recorded Conway witness set reads with
  the flat decoder's witnesses, and the deep preprod witness set reads on a small stack.
- Conway's auxiliary data has `AlonzoTxAuxDataRaw`'s fields: metadata by `Word64` label, Conway native
  scripts, and Plutus scripts' bytes per language (`Map Language (NonEmpty PlutusBinary)`). It keeps the bytes the body's auxiliary data hash is
  over, and reads the three forms Conway reads (a metadata map, the `[metadata, native scripts]` list, the
  map tagged 259). The flat decoder reads it and refuses what Conway refuses, and its fields are read again
  from the same bytes with the flat decoder's readers, so a record with a detached head reads as it does. The
  flat decoder refuses key 5 of the tagged form as unknown, where the reference reads it as Plutus V4 and
  then refuses it below decoder version 12. That is a reported-error difference, recorded with the others
  ([#796](https://github.com/KD5UJC-XYZ/scarab/issues/796)). The classes are `EraTxAuxData`, `AllegraEraTxAuxData` and `AlonzoEraTxAuxData`;
  validating auxiliary data takes the protocol version and comes with the rules.
- A Conway transaction is its body, witness set, phase-two flag (`IsPhase2Valid`) and auxiliary data,
  read by Conway's decoders. The classes are `EraTx` and `AlonzoEraTx`, at the levels of the era's bodies.
  Every recorded Conway transaction reads with the flat transaction's fee size
  (`sizeTxForFeeCalculation`) and flag, and reads the same submitted whole (`ConwayTx.fromEnvelope`, the
  form the mempool and the refusal corpus hold) as from a block's parts.
- A Conway output is written back in the form the store keeps (`toStored`, the inverse of `fromStored`),
  which the UTXO rule's update and the predicate failures that name outputs need. `fromStored` refuses a
  stored output that `toStored` would not give back, one whose kept reference-script hash or tree is not
  what its bytes read as, which only a damaged store holds (`StoredOutputDiffers`). Every recorded Conway
  output, and outputs with native, Plutus V1, V2 and V3 reference scripts, read and are written back
  unchanged.
- **The protocol parameters** (decided 2026-11-15, #85) mirror the reference's higher-kinded record
  (`PParamsHKD f era`, `Core/PParams.hs:376`): Conway's record `ConwayPParams[F[_]]` has the reference's
  fields at the reference's types, in its order, each tagged with its groups (`THKD`). With `F` the
  identity it is the parameters, with `F` an option an update, and the protocol version is the one field an
  update cannot set (`HKDNoUpdate`, a match type on `F`). The classes `EraPParams` to `ConwayEraPParams`
  have one lens per field generic in `F`, so one lens reads the parameters and an update.
  - The record is read from the stored `ProtocolParams` and the decoded `ConwayParamUpdate`, and written
    back over the stored record. No persisted format changes. The recorded Conway state's current and
    previous parameters come back unchanged; it has no future parameters.
  - Each field is at the reference's type, so a voting threshold outside `[0, 1]`, negative execution units
    or a cost model outside its `Word8` tag and `Int64` parameters is refused.
  - Against the flat rules, on generated updates and on every recorded Preview parameter change: applying an
    update, `ppuWellFormed` and `modifiedPPGroups` agree.
  - A member the reference confines to a range of eras (`AtMostEra`, `ExactEra`) is a class of its own per
    range, with instances at exactly those eras (decided 2026-11-15, #85). None of those eras has its record
    yet, so `EraPParams` lands without its five ranged members, and `AlonzoEraPParams` without
    `hkdCoinsPerUTxOWordL`.
  - The parameters' descriptions (`eraPParams`, `PParam`) are the reference's, Conway's and Dijkstra's, each
    with its name, its lens, its update's tag and lens, and how its value shows as Plutus data. An update
    shows as Plutus data through them (`ToPlutusData (PParamsUpdate era)`), in the order of the tags. It is
    the flat path's V3 changed parameters on generated updates, on every parameter change of the recorded
    blocks and of the Conway refusal corpora, and on every update of the Dijkstra parameter corpus. The
    reference's recorded contexts carry it: the PlutusV3 contexts of the two recorded Preview transactions
    that propose a parameter change, and the PlutusV4 contexts of the Leios build's governance case.
  - Not yet: the upgrade and downgrade of the parameters (`UpgradePParams`, `upgradePParamsHKD`,
    `downgradePParamsHKD`, `emptyUpgradePParamsUpdate`), which need Babbage's record and its class
    instances, and the reference's comparison of parameter records and updates (`Ord (PParamsHKD …)`, not the
    descriptions' order above), each on #85's checklist;
    `fromPlutusData`, and the CBOR and JSON instances and the era decoder a description also packs (#85).
- **Conway's rules, first predicates** (`ledger/.../era/rules/`): eight of the predicates Babbage's UTXO runs,
  as Conway's runs it (`babbageUtxoValidation`, `Babbage/Rules/Utxo.hs:325-411`), each the reference's function
  generic in its era over its classes: `disjointRefInputs`, `validateOutsideValidityIntervalUTxO`,
  `validateInputSetEmptyUTxO`, `validateBadInputsUTxO` (with `allInputsTxBodyF`), `validateWrongNetwork`,
  `validateWrongNetworkWithdrawal`, `validateWrongNetworkInTxBody` and `validateTooManyCollateralInputs`.
  - They report the node's flat failures, so each verdict is compared with the flat predicate's: on every
    transaction of the recorded Conway refusal corpora (`ConwayUtxoChecksSuite`), five of the eight refuse
    somewhere; the other three refuse on cases crafted from a recorded one, an empty input set, spent
    reference inputs at version 10 and a collateral maximum of zero.
  - The independent review found two differences from the reference that the flat predicates share, so the
    comparison could not show them. The collateral count is now narrowed to a `Word16` in both, as the
    reference's `fromIntegral` narrows it. `validateWrongNetwork` keeps each address's raw bytes where the
    reference collects decoded addresses, equal by value: a Gap of the refusal's content, tracked on #796.
- **The UTxO class and the fees** (`ledger/.../era/EraUTxO.scala`, `era/rules/`): `EraUTxO` with
  `getConsumedValue`, `getProducedValue` and `getMinFeeTxUtxo` (`State/UTxO.hs:242-285`), and Conway's
  instance of them, with the deposit and refund totals of certificates and bodies, `sizeTxF`, `getMinFeeTx`
  and the reference's parameter lenses they read. Then `feesOK` with the collateral checks,
  `validateMaxTxSizeUTxO` and `validateExUnitsTooBigUTxO`.
  - On every Conway case of the refusal corpora the minimum fee and the values consumed and produced equal
    the flat rule's, and each predicate's verdict equals the flat one's. `feesOK`, the size and the units each
    refuse somewhere in the corpus. The corpus holds no reference script, no pool registration, no
    registration and unregistration of one credential in a body, no donation and no wrong declared total
    collateral, so crafted cases compare those branches: a script carried by two outputs, past the first
    fee tier; the tiers' boundaries; the deposits and refunds of crafted certificates with a pool deposit;
    and a declared total that is not the balance.
  - `collAdaBalance` adds the lovelace in a wrapping `Word64`, as `sumAllCoin` does; the flat path adds
    exactly. They differ only past the supply, which no set of collateral outputs reaches.
  - A failure that carries an output or a value carries the node's stored form of it, through a glue class
    (`StoredTxOut`) with Conway's instance only, until each era's failure types (§3.4).
  - The scripts needed and provided and the witnesses needed came with UTXOW, and `AlonzoEraUTxO` with
    its datums (below).
- **The outputs** (`era/rules/`): `validateOutputTooSmallUTxO` with `babbageMinUTxOValue`,
  `validateOutputTooBigUTxO` and `validateOutputBootAddrAttrsTooBig`, each compared with the flat predicate on
  every Conway case of the refusal corpus; the corpus never exceeds the value size, so a crafted limit of one
  byte compares it, with several outputs reported last first.
  - `pointwise (>=)` against `inject` of the least lovelace is written out (the coin at least it, every
    quantity at least zero), which is what `pointWise` against an empty map is, so no tree enters.
  - `babbageMinUTxOValue` is called as a function: `EraTxOut`'s `getMinCoinSizedTxOut` waits for the
    parameter records of the eras before Babbage (#85).
- **The value conserved** (`era/rules/`): `validateValueNotConservedUTxO`, with `shelleyConsumed` and
  `produced` over `EraUTxO`, compared with the flat predicate on every Conway case of the refusal corpus and
  on a fee one lovelace over and under the balance. `EraCertState` gains the deposits and refunds of a
  body's certificates (`certsTotalDepositsTxBody`, `certsTotalRefundsTxBody`, with Conway's
  `conwayCertsTotalDepositsTxBody` and `shelleyCertsTotalRefundsTxBody`), equal to the flat path's on every
  case and on crafted certificates.
  - `Val` gains its superclass `Eq` as a member (`eqv`): `MaryValue`'s `pointwise (==)` is the equality of
    two canonical values, and every `MultiAssetValue` is one, made canonical by `MultiAssetValue.of` as the
    reference's `MultiAsset` is, and kept so by the arithmetic.
  - `Val` gains its size and compact-form members (`size`, `isAdaOnlyCompact`, `coinCompact`,
    `injectCompact`, `modifyCompactCoin`), with its superclass `Compactible` (`toCompact`, `fromCompact`) and
    each value's compact form: `CompactCoin` for `AdaValue`, and for `MultiAssetValue` a `CompactValue` whose
    multi-asset case holds the packed representation the MemPack codec writes (`MultiAssetRep`), built only
    by `to`. The `size` of a `MultiAssetValue` is the minimum-UTxO rule's `valueWords`. Assets too many to
    compact have no compact form here; the reference only asserts that they never come, and its decoder
    refuses them.
- **The forecast** (`era/rules/`): `validateOutsideForecast`, the end of a validity interval with redeemers
  translated through the era history extended linearly from the current slot (`PosixClock.Extended`).
  The flat rule has none, so it is compared with the reference's recorded verdicts
  (`PastHorizonReferenceSuite`: an end past the horizon passes, as the reference's refusal is the Plutus
  context's) and crafted in each branch. It refuses only where the current slot is past the history, which
  the reference never gives the ledger: a header is validated only below the tip plus one plus the
  stability window, and the history runs at least that far, its safe zone being the stability window
  from the tip plus one, or from the next era's start once that is known (ouroboros-consensus 82ecba32
  `Shelley/Ledger/SupportsProtocol.hs:100-127`, `Shelley/Ledger/Ledger.hs:171-185`,
  `HardFork/Combinator/State/Infra.hs:194-262`). Scarab's header gate waits past the forecast too, so
  the node switch changes no verdict here.
- **UTXO's checks put together** (`era/rules/`): `babbageUtxoValidation`, the predicates in the reference's
  order with every failure accumulated, the six it runs with `runTestOnSignal` skipped when the policy does
  not validate the signal, and the failures of Shelley's predicates injected as the era does
  (`ConwayUtxo.injectShelleyFailure11_1` at f649f975, `injectShelleyFailure11_2` at the 11.2 and Leios
  pins). It equals the flat UTXO's verdict on every Conway case of the refusal corpus, under every build
  and both policies; the corpus has cases with several failures and cases whose failure differs by build.
  Where only UTXO failed and the mempool's measure let the transaction through (15 cases, both builds'
  bootstrap attributes among them), its failures encode to the reference's refusal byte for byte. The flat
  UTXO has no forecast check, so a crafted case pins its place after the validity interval.
  - What it reads besides its environment (the network, the system start, the era history at the tip, the
    policy, the injection) comes in `UtxoContext[E]`: the node's `Globals` has no era history. The
    injection is a value per era and build that only the rules package makes (`InjectShelleyUtxoFailure`),
    chosen by build until the builds are types (`BuildRules`, #1063).
  - Still to port before UTXO is whole: UTXOS and the state's update (`updateUTxOState`,
    `updateTreasuryDonation`), on the annotated transaction (`StAnnTx`, below), which UTXO takes as its signal
    once it moves onto it (#85).
- **Conway's rules, the first predicates of UTXOW** (`era/rules/Utxow.scala`): six of the predicates
  Babbage's UTXOW runs (`babbageUtxowTransition`, `Babbage/Rules/Utxow.hs:308-391`), the ones whose own
  arguments the era types have: `validateVerifiedWits`, `babbageMissingScripts`, `missingRequiredDatums`,
  `hasExactSetOfRedeemers`, `validateNeededWitnesses` and `checkScriptIntegrityHash`, with the script
  integrity it compares (`ScriptIntegrity`, `mkScriptIntegrity`, `hashScriptIntegrity`, and the language views
  `getLanguageView` and `encodeLangViews`).
  - Their verdicts equal the flat checks' on every transaction of the recorded Conway refusal corpora
    (`ConwayUtxowChecksSuite`), where the scripts, the datums, the redeemers and the integrity hash refuse and the
    signatures never do. On every recorded Conway transaction, which has no UTxO set, the signature check's
    verdict and the integrity check's equal the flat ones (`EraUtxowSuite`). Crafted cases refuse each: forged key and
    bootstrap witnesses, an extraneous script, a wrong integrity hash at versions 10 and 11, and datums
    without redeemers, whose integrity is over the era's empty redeemers. The integrity's bytes equal the
    flat preimage for every set of languages, and each language's view, with and without its cost model,
    equals bytes written out by hand. Views of one tag are encoded in their derived order, as the
    reference's stable sort of `Set.toList` leaves them, whatever order the set was built in.
  - A witness set without redeemers has the era's empty redeemers, whose bytes the reference encodes at the
    era's lowest protocol version. That version comes from the reference's `Era` class, here a class of its
    own with `eraProtVerLow` and `eraProtVerHigh` (`era/Era.scala`), with instances for Allegra, Mary,
    Babbage, Conway and Dijkstra. Each instance's versions equal the range the flat decoders read the era at.
  - The signature check lists the keys of failing key witnesses in the witness set's order (`Ord WitVKey`),
    as the reference does, and as the flat check does. The bootstrap witnesses follow, in the order of
    `bootAddrTxWits`, which the decoder keeps in the reference's set order (by address root); crafted cases
    fail them alone and after key witnesses, in that order in both paths.
  - The scripts needed and provided are `EraUTxO`'s (`ScriptsNeeded`, Alonzo's form `AlonzoScriptsNeeded`,
    `getConwayScriptsNeeded` and `getBabbageScriptsProvided`), and `babbageMissingScripts` reads them. The
    scripts needed equal the flat path's on every case of the refusal corpus and every recorded Conway
    transaction, in order and with each purpose's index; the scripts provided equal the flat path's on
    every case of the refusal corpus, which has the UTxO set their reference scripts come from. A crafted
    case puts a script input after another, writes two policies out of their order and a script
    certificate second, where a wrong order or index would show. Alonzo's spending, withdrawing and minting needs take the evidence that the era's purpose is
    Conway's, as `AlonzoEraScript`'s purpose builders are not members yet (#85).
  - The keys that must sign are `EraUTxO`'s `getWitsVKeyNeeded` (`getConwayWitsVKeyNeeded`: Shelley's keys
    without the governance ones, of the spendable inputs, certificates, pool owners and withdrawals, then
    the required signers and the voters), which `validateNeededWitnesses` reads against the witnesses' keys
    (`keyHashWitnessesTxWits`). It gives the flat check's verdict on every case of the refusal corpus, and
    where the reference refused a transaction for its missing witness alone, its failure encodes to the
    reference's refusal byte for byte.
  - The datums are `AlonzoEraUTxO`'s, with its one member yet, `getSupplementalDataHashes` (Conway's is
    `getBabbageSupplementalDataHashes`: the outputs, the collateral return and the outputs of the reference
    inputs), which `missingRequiredDatums` reads with the datums the spent Plutus outputs name
    (`getInputDataHashesTxBody`, a script's language read through `withPlutusScript`, now a member of
    `AlonzoEraScript`). `hasExactSetOfRedeemers` compares the redeemers with the Plutus scripts needed
    (`extSymmetricDifference`), its purposes Conway's by evidence, as above. The node's flat failure names a
    missing redeemer's purpose with its index and item and, for a spending one, the output spent, so the caller
    gives each purpose's flat form (`ConwayPlutusPurpose.toStored`, glue). Both give the flat checks' verdicts
    on every case of the refusal corpus, where each refuses, and where the reference refused a transaction for an
    unallowed supplemental datum alone, its failure encodes to the reference's refusal byte for byte. The corpus
    spends only Plutus V3 outputs, so crafted cases spend one without a datum as Plutus V3 (no failure, CIP-69)
    and as Plutus V1 (unspendable), add redeemers no purpose needs out of their order, and need a Plutus script
    to mint, certify, withdraw and vote with no redeemer.
  - The failing native scripts wait (#85); `getSpendingDatum` came with the script context, and the members of
    `AlonzoEraUTxO` that read the annotated transaction, the auxiliary data's validation and the well-formed
    scripts came with the annotated transaction (`StAnnTx`, below). UTXOW's transition on it waits (#85).
- **The Plutus V3 script context** (`era/PlutusContext.scala`, `era/PlutusLedgerApi.scala`, the `TxInfo`
  modules `era/PlutusTxInfos.scala`, `era/AlonzoTxInfo.scala`, `era/BabbageTxInfo.scala` and
  `era/conway/ConwayTxInfo.scala`): the reference's `EraPlutusTxInfo l era` (`Alonzo/Plutus/Context.hs`), with
  `LedgerTxInfo`, `PlutusTxInfoResult` and the language families as match types over the language, and
  Conway's instance for Plutus V3 with its translations (`Conway/TxInfo.hs`), each over Alonzo's, Babbage's and
  the core's that it calls.
  - The context's values are the types of plutus-ledger-api (plutus 507cab8c, `V1`, `V2`, `V3`), each with
    its `ToData` encoding as the Template Haskell derives it, so each translation builds the value the
    reference builds and serialises to the reference's bytes; a map is an association list, in the order
    and with the repeats the translation gives it.
  - Each failure is the era's context error (`ContextError`, Conway's `ConwayContextError` carrying Babbage's
    and Alonzo's, injected as the reference's `Inject` instances do). Two differ in representation: a time
    past the horizon carries the clock's typed failure where the reference's carries a Text, and an output's
    inline datum, the era's `BinaryData` read through `binaryDataToData`, fails as the node's own
    `Undecodable` (as in Dijkstra's flat context) where the reference raises `error`. An output's datum is
    the reference's `Datum era`: an inline datum is read as data where the output is read from a block or a
    transaction (the reference's `DecCBOR`) and taken unread where it is read from the store (its
    `MemPack`), so only an inline datum taken unread can reach that failure. Only
    `BabbageTxOutRep.fromStored` takes one so, which the type of its evidence (`StoreRead`) enforces;
    its callers are trusted to pass an output read from the store, as the reference trusts its
    `MemPack`, until a store that reads the era's outputs itself makes that a type (https://github.com/KD5UJC-XYZ/scarab/issues/85#issuecomment-6989194084). A redeemer and a witness
    datum are the witness set's decoded `Data`, so their translation cannot fail. The epoch information and the system start are the flat translation's one
    clock.
  - Two members the context needs: `redeemerPointerInverse` on `AlonzoEraTxBody` (Conway's
    `conwayRedeemerPointerInverse`, through `Indexable`'s `fromIndex`) and `getSpendingDatum` on
    `AlonzoEraUTxO` (`getBabbageSpendingDatum`), its spending purpose read through the evidence that the era's
    purpose is Conway's; its datum is `Data`, an inline datum read as data where it is asked for, as the
    reference's strict `SJust` forces it, and its failure returned where the reference raises `error`.
  - `EraPlutusContextSuite`: on every Conway row of the reference's recorded contexts, context matrix,
    context errors and script results (432 transactions), the typed V3 `TxInfo` equals the flat one, or both
    refuse alike, at the row's version and at 9, 10 and 11, whatever the languages its scripts use. Each of
    the 142 Plutus V3 contexts the reference collected there (54 recorded, 64 of the matrix, 24 of the script
    results) is the typed one byte for byte. On the 62 Conway transactions of the recorded blocks, every
    input given one stand-in output as the blocks carry no UTxO set, the two `TxInfo`s are equal at 9, 10
    and 11; their proposals hold all seven actions. `ConwayPlutusContextRefusalsSuite` (node) compares them
    on the 84 Conway cases of the refusal corpora, with the UTxO set each was refused against, Byron
    outputs and missing inputs among them. Crafted cases cover what
    the corpus lacks against the flat translation: a missing input, a clock that cannot translate the slot,
    an undecodable inline datum, reference inputs that are inputs (refused from version 11 alone), the
    hard-fork, no-confidence and committee actions with members of both kinds out of order, the
    certificates the corpus lacks with a deposit and a refund left out at version 9, withdrawals and mints out of order, and pointers past
    each container's end.
  - The node still builds contexts with the flat translation.
- **Plutus V1 and V2, and the era's script contexts** (the same modules): Conway's instances for Plutus V1
  and V2 (`Conway/TxInfo.hs:399-480`) with the translations they call (Conway's `transTxOutV1`,
  `transTxInInfoV1`, `guardConwayFeaturesForPlutusV1V2`, `transTxCertV1V2` and `transPlutusPurposeV1V2`,
  Alonzo's V1 output, withdrawals, mint, common certificates and legacy arguments, Babbage's V2 input and
  arguments), so the language families have every case the reference's have (V4's as V3's types), the
  inputs' without Alonzo's own V1 case. `EraPlutusContext` is the reference's class with
  `mkSupportedLanguage`, `mkTxInfoResult` and `lookupTxInfoResult`, Conway's instance memoising each
  language's information lazily (`ConwayTxInfoResult`), and `SupportedLanguage` equal and ordered by its
  language.
  - Each language's instance selects its own information from the memoised result
    (`EraPlutusTxInfo#lookupTxInfoResult`), so the reference's impossible lookup of a language the era does not
    support (`lookupTxInfoResultImpossible`) has no counterpart. The redeemer and the datum of the legacy
    arguments are read from their bytes after the purpose, whose failure is the reference's.
  - `EraPlutusContextSuite`: the typed V1 and V2 `TxInfo`s, through the era's memoised result, equal the flat
    ones or refuse alike on the same 432 rows and the 62 recorded block transactions, at the row's version and
    at 9, 10 and 11, reaching every refusal a V1 or V2 context has in Conway but `PlutusPurposeNotSupported`,
    which the guard on votes and proposals reaches first, as in the reference. Each of the 141 Plutus V1 and
    V2 requests the reference collected there (14 recorded, 78 of the matrix, 49 of the script results) has
    the typed arguments byte for byte. `ConwayPlutusContextRefusalsSuite` compares the V1 and V2 `TxInfo`s on
    the 84 refusal-corpus cases too.
- **The scripts of a transaction with their contexts** (`era/PlutusEvaluate.scala`; `Plutus/Evaluate.hs`,
  `Alonzo/Plutus/Evaluate.hs`, `Alonzo/Plutus/TxInfo.hs:100-127` and `Alonzo/UTxO.hs:431-465`, cardano-ledger
  f649f975): `PlutusRunnable`, `SupportedPlutusRunnable` (equal by language and script hash),
  `EraPlutusContext#mkSupportedPlutusRunnable` with Conway's instance, `CostModel`, `PlutusWithContext`, the
  era's `CollectError`, and Alonzo's `resolveNeededPlutusScriptsWithPurpose`, `mkPlutusWithContext`,
  `scriptsWithContextFromLedgerTxInfo` (with its `merge`, the successes and the failures each in reverse order)
  and `collectPlutusScriptsWithContext`. `hoistPlutusPurpose` is a member of `ConwayEraScript`, as Babbage's
  purposes are not among the era types.
  - A runnable script is its binary and its hash, the hash computed from the binary when first asked for, as
    the reference's lazy field, or installed by the resolver from the key of the provided script: the reference also holds it decoded for the protocol version,
    but decoding is the `plutus` module's capability (`ScriptDecoder`), whose `Unavailable` is a defect rather
    than a verdict, so its result is not held in ledger data. A `PlutusWithContext` becomes the evaluator's
    request (`EvalRequest`) with the arguments as each language applies them.
  - A cost model's language is its type's index and its parameters `Int64`s, so a script's model is of the
    script's language; it is made only by the lookup among the parameters' models, by the language's tag: a
    known language's model is always valid after decoding
    (`mkCostModelsLenient`, `CostModels.hs:426-440`, and `CostModelDecode`), so the tag is the reference's
    `costModelsValid` lookup; the split of the valid and the unknown models is #85's item on the order of the
    parameters.
  - The spending datum is read before the arguments are made; its failure, which only an inline datum taken
    unread can reach, is the node's own `Undecodable` with the decoder's failure, where the reference raises
    `error`, as an output's inline datum's is in the contexts.
  - `EraPlutusContextSuite`: the scripts collected with their contexts, as the evaluator's requests, equal the
    flat path's (`Phase2.collect`) in order on the same 432 rows and 62 recorded block transactions, or both
    fail with the same failures in the same order, compared after normalisation (a missing redeemer by its
    purpose's tag and index, a translation's failure as the flat one), reaching a missing redeemer, a missing cost model and a
    translation's failure. A language whose tag the models lack gives `NoCostModel`, and an unknown tag is no
    language's model. Each runnable made from a script has that script's hash; the resolver gives a provided script's runnable
    the key it is provided under, which is trusted to be its hash, as the reference trusts it. A needed script's cached runnable is the one used,
    and a script the cache holds that the transaction does not provide meets no need.
  - The collection is of a top-level transaction, without the reference's empty memo of sub-transactions'
    contexts, which only Dijkstra fills (#85's item on Dijkstra's script contexts).
- **The annotated transaction and its UTXOW predicates** (`era/StAnnTx.scala`, `era/EraTxAuxData.scala`,
  `era/rules/Utxow.scala`; `Core.hs:180-188`, `Alonzo/Tx.hs:556-565`, `Alonzo.hs:121-160`,
  `Alonzo/UTxO.hs:154-214`, `Alonzo/TxAuxData.hs:363-373,494-515`, `Shelley/Rules/Utxow.hs:427-443` and
  `Babbage/Rules/Utxow.hs:234-276`, cardano-ledger f649f975): the families `StAnnTx` (Conway's `AlonzoStAnnTx` at
  the top level alone) and `StAnnTxCache`, `EraTx#txStAnnTx` and `#cacheStAnnTx`, Alonzo's `mkAlonzoStAnnTx` (a
  function until the mempool brings `ApplyTx`'s `mkStAnnTx`), `AlonzoEraUTxO`'s four members of the annotated
  transaction, `EraTxAuxData#validateTxAuxData` with `hashTxAuxData`, `isValidScript` over the cache of runnable
  scripts, and the predicates `validateMetadata` and `validateScriptsWellFormed` (with its `TxOuts` form), which
  report the flat `UtxowFailure`s.
  - A script's well-formedness is the `plutus` module's decoder's verdict, the decoder given as an argument; a
    script it gives no verdict for (`Unavailable`) is returned (`UnavailableScript`), and the predicates report it
    as the flat checks' defect.
  - `EraPlutusContextSuite`: on the same 432 rows and 62 recorded block transactions, the annotated transaction
    holds what its members compute (the scripts provided and needed, the cache, the languages used and the
    scripts with their contexts), and a cached runnable is kept; `validateMetadata` and
    `validateScriptsWellFormed` give the flat checks' verdicts (`TxChecks.auxiliaryChecks`,
    `TxChecks.scriptsWellFormed`), and so they do on auxiliary data without its hash, a hash without its data,
    another hash, a malformed auxiliary Plutus script, the last two together, a malformed witness script, a
    malformed reference script on an output and on the collateral return, a malformed witness and reference
    script together, and a Plutus V3 script at protocol 8 and 9. The reference's recorded refusal of a
    malformed witness and reference script (the Conway devnet's refusal corpus) is the predicate's, hash for
    hash, and a cache hit is checked by the cached runnable's binary. The reference's 210 Conway observations
    of auxiliary data (`fixtures/reference/auxiliary-scripts`) are the era types' too: decoded where the
    reference decodes, valid where its `validateTxAuxData` is, and `validateMetadata`'s `InvalidMetadata`
    exactly where it is not. No pinned observation of a malformed collateral return's reference script, or of
    well-formedness by protocol major, exists yet (tracked on #922); those cases are compared with the flat
    check alone.
  - Where the decoder gives no verdict for several scripts, the defect names the first in the order they
    arrived (the auxiliary scripts in their encoded fields' order, the witnesses' native, Plutus V1, V2 and V3
    lists, then the outputs' reference scripts in the outputs' order, the collateral return's last), as the
    flat checks do; the reference's decoding always gives a verdict, so it has no such choice. The witness set
    and the auxiliary data keep that order for it (`scriptTxWitsArrival`, `plutusScriptsArrival`).
    `validateMetadata` reads the scripts only where the data and its hash are both present, the reference's
    order; the flat check reads them first, so data without its hash, with a script the decoder gives no
    verdict for, is the predicate's `MissingTxBodyMetadataHash` and the flat check's defect.
  - Not yet (#85): UTXOS, LEDGER and the mempool on the era types. The node still builds contexts and checks
    transactions with the flat rules.
- **Proposals and votes** (`era/Procedures.scala`, `conway/ConwayProcedures.scala`): `GovAction[E]`,
  `ProposalProcedure[E]` and `VotingProcedures[E]` are data types parameterised by the era, as the reference's
  `data GovAction era` and the others are, not families (decided 2026-11-16, #85): a family with one case reduces, in
  generic code, to that case, so a rule generic in its era (GOV, shared by Conway and Dijkstra) could read a
  proposal's fields only while Conway is the only case. What differs between the eras is the update
  (`PParamsUpdate[E]`), so `GovAction[Conway]` and `GovAction[Dijkstra]` are distinct types. Each parent is the id
  of its action's purpose (`GovPurposeId[P]`, nominal in `P`). Conway's values are made only from what its decoder
  read; a proposal keeps the flat `Proposal` it was read as, which the flat failures carry, and is equal by its
  typed fields. Every recorded Conway proposal, of each of the seven actions, reads with the flat fields, and
  crafted proposals at each bound and past it give the flat decoder's verdict and error.
- **GOV's first functions** (`era/rules/Gov.scala`): `isBootstrapAction`, `checkBootstrapProposal`,
  `actionWellFormed`, `checkGuardrailsScriptHash` and `unelectedCommitteeVoters`, with Conway's version gates and the
  committee's authorised hot credentials, each generic in its era over the reference's classes. They report the
  node's flat failures; `actionWellFormed` takes the action as read for its failure.
  - `ConwayGovChecksSuite` compares the failures they report with those the flat GOV rule records for the same
    checks, on every Conway case of the refusal corpus and on crafted ones. The corpus never fails
    `checkBootstrapProposal`; a crafted case does.
  - Both paths list unelected committee voters as the reference does, the ascending list of a set, whatever order
    the body's map arrived in.
  - The functions that read the stored proposals, and the checks `conwayGovTransition` writes inline, wait for the
    governance state's era types (`EraGov`, tracked on #85).
- **Conway's certificate rules** (`ledger/.../era/rules/Certs.scala`): CERTS, CERT, DELEG, GOVCERT and
  Shelley's POOL, each the reference's transition under its name, generic in its era over the state classes
  `EraAccounts`, `ConwayEraAccounts`, `EraCertState` and `ConwayEraCertState` (Conway's instances over the
  records the node keeps), and run on the same judgment as the flat rules.
  - From the same ticked state they judge every transaction of the recorded Conway refusal corpora, at its
    protocol version and at 9, 10 and 11, as the flat rules do: the failures in order, or the certificate
    state (`ConwayCertsChecksSuite`); so do the transactions of the recorded Preview Conway blocks, judged
    against a recorded state at 9, 10 and 11. A refusal carries no state, so crafted sequences the rules accept
    compare the states of delegations moved, pools re-registered and retired, and committee members.
  - The corpus records most of their failures; a pool's wrong network, oversized metadata hash, low cost
    and repeated VRF key, and a resigned committee member, only crafted certificates reach. A test fails if
    any failure of the rules is reached by neither.
  - The epoch sums (a DRep's expiry, the dormant shift, the retirement limit) wrap in `Word64` as the
    reference's `addEpochInterval` and `binOpEpochNo` do (`CertsEpochWrapSuite`). The flat rules sum them
    as integers, refusing an expiry outside `EpochNo` and comparing a retirement with an unbounded limit, so
    the two paths differ only past epoch 2^64; that gap is the flat rules' (#85), which stage 3 deletes.
  - `EraCertState` landed without the deposit and refund totals of a body, which the value conserved
    adds (above). Two more gaps are on #85: the committee and its proposals as the node's records in the
    environments, and a pool's parameters with the node's field types.
- **Next, Conway's rules.** The first rule ported is one that needs no parameters, the second one that
  does, each under a differential against the flat rule on the recorded blocks and the refusal corpus.
- The body is read through the flat decoder for now. What only the projection refuses (an update, genesis
  delegation, instantaneous rewards) the flat Conway decoder refuses first, with the same error, as tests on
  crafted bytes show.
- The spike's Babbage body is removed, so no partial body remains, and Babbage keeps its output and script
  types. A function written once over the body classes (`EraTxSuite`) runs in Conway alone until Dijkstra's
  body is typed (stage 3).

**What it does not show yet:** a full rule pipeline (LEDGER) on typed transactions, which is stage 2.
Bodies decoded from recorded blocks are compared on id, fee, output bytes and one era-only field.

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
