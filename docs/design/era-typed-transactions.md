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
    `hkdCoinsPerUTxOWordL`. Of the bodies' ranged members, the required signers' field
    (`reqSignerHashesTxBodyL`, of the eras Alonzo to Conway) is `AlonzoEraTxBodyToConway`. Its only
    instance yet is Conway's, as Conway is the only era with a typed body; Alonzo's and Babbage's come
    with their bodies. Dijkstra's body, whose field the reference fills with `notSupportedInThisEraL`,
    will have none. The getter of every era (`reqSignerHashesTxBodyG`) stays on `AlonzoEraTxBody`: the
    field up to Conway, and in the reference the guards' key hashes in Dijkstra
    (`Dijkstra/TxBody.hs:1419`, cardano-ledger 9df9f98c), of which there is no typed instance yet.
    Shelley's `ttlTxBodyL` comes as a class with Shelley's body. This is a Deviation, recorded in the
    [decisions register](../decisions.md#reference-fidelity).
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
  - Each verdict is compared with the flat predicate's, the era failure in the flat form (UTXO whole, below):
    on every transaction of the recorded Conway refusal corpora (`ConwayUtxoChecksSuite`), five of the eight refuse
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
  - A failure carries the era's outputs and values; its flat form carries their stored form, through a glue
    class (`StoredTxOut`) with Conway's instance only (UTXO whole, below).
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
  - UTXOS, the state's change and UTXO's transition are on the era types too (below).
- **UTXOS and the change of the UTxO state** (`era/rules/Utxos.scala`, `era/rules/Utxo.scala`,
  `era/EraStake.scala`, `era/PlutusEvaluate.scala`; the `Rules/Utxos.hs` of Alonzo, Babbage and Conway,
  `Shelley/Rules/Utxo.hs:584-647`, `Babbage/Rules/Utxo.hs:449-487`, `Babbage/Collateral.hs`,
  `Conway/Rules/Utxo.hs:201-209`, cardano-ledger f649f975, where 29a7d656 differs only in derived
  instances):
  - Conway's `utxosTransition` and `conwayEvalScriptsTxValid`, Babbage's `expectScriptsToPass` and
    `babbageEvalScriptsTxInvalid`, and Alonzo's `when2Phase`, on the annotated transaction. The collection's
    failures are recorded, then the scripts are evaluated only when the policy validates every check and
    nothing failed so far in the judgment (`whenFailureFree`).
  - The failures and events are the reference's own types, not the flat `UtxosFailure`, unlike UTXO's checks
    above. Conway's injection (`alonzoToConwayUtxosPredFailure`, `alonzoToConwayUtxosEvent`) has PPUP's
    failure and event uninhabited at Conway, as `absurdEraRule` has them; `EraRuleFailure "UTXOS"`,
    `EraRuleEvent "UTXOS"` and PPUP's are match types with Conway's case.
  - Two differ in representation. A script's failure (`ScriptFailure`, `FailureDescription`) keeps the
    evaluator's typed failure and the script with its context, from which the reference writes its text and
    Base64. A script the evaluator gives no verdict for is raised (`ScriptVerdictUnavailable`), as the flat
    rule's `Unavailable` is, and is never a verdict.
  - Evaluation is ledger-core's `ScriptResult` with Alonzo's `evalPlutusScripts`, over the `plutus` module's
    `ScriptEvaluator[F]`. Events are told through `Tell`, and the judgment is `Judge`. Conway's UTXOS has no
    environment and no state, so its transition takes the policy and the annotated transaction.
  - The state's change is Shelley's `updateUTxOState`, `updateUTxOAndInstantStake` and
    `updateUTxOStateDeposits`, Babbage's `updateUTxOState` with `collOuts` and `mkCollateralTxIn`, Conway's
    `updateTreasuryDonation`, `txouts`, UTXO's events (`AlonzoUtxoEvent`), and `EraStake` with Conway's
    instant stake. `txouts`, `collOuts` and `mkCollateralTxIn` take the body's fields to the functions of the
    same names in `TxIn`'s companion, the one home of the outputs' numbering (#1252), which the flat rules use
    too.
  - It changes the node's `UTxOState[E]`. The outputs spent are read from the store into the era
    (`StoredTxOut.fromStored`) and those created are written back. `Map.union` stays left-biased. A damaged
    store or an output outside the state's vocabulary is a raised `UtxoStateDefect`. Nothing persisted
    changes.
  - `EraUtxosSuite` compares the era rule with the flat one (`rules.Utxos`, `rules.Utxo.update`) and with
    what the reference recorded. It checks these:
    - On the 70 Conway transactions the reference node was given (the Plutus V1 context submissions at
      protocol 9 and 10, the context-error and the Plutus boundary submissions), with each flag, each
      policy, a failure recorded before or not, and an evaluator that gives no verdict, both rules give the
      same verdict. Both find the same context errors by kind and in order, the same tag mismatch with each
      script's typed failure and request, the same number of scripts evaluated, or none where the policy or
      an earlier failure skips them (an evaluator that fails the suite if asked).
    - As submitted, each verdict is the reference's acceptance or refusal, nothing more.
    - On the reference's multi-script results (`fixtures/reference/script-results`, 25 rows under both
      flags: three scripts in each of the eight patterns of passes and failures in Plutus V1, V2 and V3),
      the groups of script hashes the events and the tag mismatch name are the reference's passing and
      failing groups, in its order, and the evaluator is asked for the scripts in the order the reference
      ran them, each with the reference's verdict. An evaluator that stops answering after one script
      leaves exactly that call and the next, and the defect names the script it stopped at.
    - The state after equals the flat rule's where the scripts pass as the flag says. It also equals it on
      the Conway transactions of the recorded blocks over an empty UTxO set: those flagged valid, and those
      flagged invalid that have redeemers (a transaction flagged invalid with none never reaches the
      change). Crafted ones donate and deregister an account that holds a deposit, which the blocks lack.
    - An admitted transaction flagged invalid keeps its inputs and loses its collateral, as the reference's
      recorded effects show. Crafted to hold its collateral and return part of it, it loses the collateral,
      creates the return at `mkCollateralTxIn` with its value, pays the rest as fees and moves the instant
      stake by the return less the collateral.
  - Where the flat rule differs, no reachable case shows it:
    - a transaction flagged invalid with no redeemer pays no collateral in the flat change
      (`TxChecks.collateral`), where the reference's pays it, but UTXOS refuses every such transaction;
    - the flat union of the outputs created with the UTxO set keeps the created output where both have
      one, the reference's keeps the set's, and both have one only for a transaction id already in the set.
  - Incomplete (CNF-2), the projections the comparison makes:
    - the context errors are compared by kind and order, their contents being `EraPlutusContextSuite`'s,
      whose corpus does not hold the four Plutus boundary submissions;
    - the tag mismatch's diagnostics are compared with the flat rule's typed failure and request, not with
      the reference's text and CBOR (the era failures have no encoder yet; the flat rule's are compared,
      `ContextRefusalReasonSuite`, and the diagnostics' remaining parity is #796);
    - the events are compared as UTXOS's own; their embedding in UTXO's (`UtxosEvent`) is not exercised;
    - the bound of `txouts` and the clamp of `mkCollateralTxIn` are `TxOutsBoundSuite`'s, and the two
      `UtxoStateDefect`s, which only a damaged store or an output outside the state's vocabulary gives, are
      not reached.
  - Not yet (#85): Alonzo's and Babbage's own UTXOS transitions, which run PPUP; and the node's phase-2
    counters (`PlutusStats`) and its preparation of a block's evaluations, which the events stand for until
    the switch.
- **UTXO whole, with the reference's failures** (`era/rules/Utxo.scala`, `era/rules/UtxoFailures.scala`; the
  `Rules/Utxo.hs` of Shelley, Allegra, Alonzo, Babbage and Conway, cardano-ledger f649f975, where 29a7d656 and
  9df9f98c differ in derived `Ord` instances, Alonzo's and Conway's units as `OrdExUnits`, the units read
  through `getTotalExUnits`, and Allegra's injection below):
  - UTXO's failures are the reference's own types, one per era (`ShelleyUtxoPredFailure` to
    `ConwayUtxoPredFailure`), with `EraRuleFailure "UTXO"` a match type with Conway's case. Each predicate above
    reports its own era's type, and `babbageUtxoValidation` and `feesOK` inject them as the reference's
    `InjectRuleFailure "UTXO"` instances do (`InjectUtxoFailure`, with Conway's `babbageToConwayUtxoPredFailure`,
    `alonzoToConwayUtxoPredFailure` and `allegraToConwayUtxoPredFailure`). Shelley's injection is Allegra's
    `shelleyToAllegraUtxoPredFailure` then Conway's; the 11.1 pin's turns the bootstrap attributes' failure into
    `OutputTooBigUTxO`, the 11.2 and Leios pins' keeps it, so it stays a value per build in `UtxoContext`.
  - Conway's `conwayUtxoTransition` on the annotated transaction: `babbageUtxoValidation`, every failure
    recorded; UTXOS as its sub-rule (`Judge.nest`), its failures and events embedded as the reference's
    `Embed (UTXOS era) (UTXO era)` does (`EmbedUtxosInUtxo`); then Babbage's `updateUTxOState` after
    `updateTreasuryDonation`, from the state the transition was given, whatever was recorded. The UTxO set
    the predicates read is the outputs of the body's inputs, collateral inputs and reference inputs, read from
    the store into the era: every output any predicate reads. A script with no verdict
    (`ScriptVerdictUnavailable`) and a damaged store or an output outside the state's vocabulary
    (`UtxoStateDefect`) stay their own typed raises, as UTXOS's and the state change's are.
  - The era failures have no encoder yet. `ConwayUtxoPredFailure.toStored` gives a failure's flat form, glue
    that the comparisons with the flat rules read. It is one-way and not lossless. A failed script keeps the
    evaluator's request but not the runnable's script hash, which the typed `PlutusWithContext`'s equality also
    holds. UTXOS's `CollectErrors` are returned as they are, since the flat failure holds their purposes and
    context errors in its own vocabulary.
  - `EraUtxoSuite` runs the whole rule and the flat UTXO (`rules.Utxo`) in one judgment from the same state on
    the 70 Conway submissions of `EraUtxosSuite`, as submitted and with the flag the other way, under each build
    and each policy. The failures are the same in the same order, UTXO's own in the flat form, a script's failure
    by its error, trace and request, and a collection's as the incomplete item below says. An admitted
    transaction leaves the same state, and the counters UTXOS's events give, embedded in UTXO's, are the flat
    rule's. With a maximum transaction size of one byte, UTXO's failure is recorded and UTXOS evaluates no script
    (an evaluator that fails the suite if asked).
  - `ConwayUtxoChecksSuite` does the same on every Conway case of the refusal corpora, with each case's UTxO
    set and certificate state, under each build and policy, cases with several failures among them. Where
    the reference refused a transaction for UTXO alone, the whole rule's failures, run at an odd depth as
    under the reference's MEMPOOL, LEDGER and UTXOW, encode to the reference's refusal byte for byte. Three
    such cases are left out by name, `tx-too-large`, `tx-too-large-spent-inputs` and `utxo-mint-units-too-big`:
    the mempool's measure refuses them before the ledger, so their recorded refusal is the measure's, not a
    refusal of UTXO whole. The suite asserts that the measure (`Capacity.phase1`) refuses these three cases and
    no other, so a case it comes to refuse fails the suite instead of leaving the comparison. Some cases are
    admitted under a policy, and UTXOS refuses some. The predicates' comparisons above keep their expected
    values, each failure injected into Conway's and put in the flat form.
  - `PastHorizonReferenceSuite` runs the whole rule on the reference's recorded Conway refusals of an end
    past the forecast horizon, with the recorded state, UTxO set and era history: its only failure is UTXOS's
    collection error: the time past the horizon (`TimeTranslationPastHorizon`), with the clock's own failure
    for the interval's end, complete and typed, the reference's refusal. The recorded capture has one redeemer,
    and the reference's collector stops translating contexts at the first failure.
  - Incomplete (CNF-2): of a collection's failures, a missing redeemer is compared by its purpose in the flat
    form (a purpose with none fails the comparison), a missing script by its hash and a missing cost model by its
    language, but a context's failure to translate (`BadTranslation`) by its kind alone, its contents being
    `EraPlutusContextSuite`'s, since the flat failure holds them in its own vocabulary; a script's failure is
    compared by its cause and the evaluator's request, which the flat form keeps, not by the runnable's script
    hash, which the typed `PlutusWithContext` also holds; the reference's multi-script results
    (`fixtures/reference/script-results`) are compared at UTXOS alone (`EraUtxosSuite`), not through UTXO whole;
    the refusals are compared through the flat form and the node's encoder, not an encoder of the era failures
    (#796); the pre-Conway UTXO fixture (`fixtures/reference/pre-conway-utxo`) holds pre-Conway bodies with one
    Conway collateral predicate's verdict, not transactions Conway's UTXO judges, so it stays with the flat
    predicates' suite; the recorded verdicts on a certificate's redeemer index
    (`fixtures/devnet/redeemer-index.jsonl`) are UTXOW's, run with its transition (UTXOW whole, below); the forecast
    corpus's Alonzo and Babbage cases, and the pre-Conway predicates of `remaining-predicates` and
    `instant-stake`, are of eras whose UTXO is not typed yet.
  - UTXOW's transition with UTXO embedded and the failing native scripts came after (UTXOW whole, below). Not
    yet (#85): the node's phase-2 counters (`PlutusStats`), which the events stand for until the Conway
    switch. The defects stay typed raises
    until then too: the node's `LedgerFailure.Unavailable` names a script by its flat redeemer, which a
    `PlutusWithContext` does not carry, and has no case for a stored output the era does not read.
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
    certificate second, where a wrong order or index would show. Alonzo's spending, withdrawing and minting
    needs build their purposes through the script class (below, the UTxO functions for any era).
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
  - `getSpendingDatum` came with the script context, and the members of `AlonzoEraUTxO` that read the annotated
    transaction, the auxiliary data's validation and the well-formed scripts came with the annotated
    transaction (`StAnnTx`, below). The failing native scripts, the predicates' own failure types and UTXOW's
    transition came with UTXOW whole (below).
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
    `AlonzoEraUTxO` (`getBabbageSpendingDatum`), its spending purpose read through the script class's
    `toSpendingPurpose`; its datum is `Data`, an inline datum read as data where it is asked for, as the
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
  `transTxInInfoV1`, `guardConwayFeaturesForPlutusV1V2` and `transTxCertV1V2`, Alonzo's purposes in the V1 and
  V2 form (`transPlutusPurpose`, which replaced f649f975's Conway `transPlutusPurposeV1V2` with the shape of
  9df9f98c, below), Alonzo's V1 output, withdrawals, mint, common certificates and legacy arguments, Babbage's V2
  input and arguments), so the language families have every case the reference's have (V4's of plutus-ledger-api's
  `V4`, below), the inputs' without Alonzo's own V1 case. `EraPlutusContext` is the reference's class with
  `mkSupportedLanguage`, `mkTxInfoResult` and `lookupTxInfoResult`, Conway's instance memoising each
  language's information lazily (`ConwayTxInfoResult`), and `SupportedLanguage` equal and ordered by its
  language.
  - Each language's instance selects its own information from the memoised result
    (`EraPlutusTxInfo#lookupTxInfoResult`), so the reference's impossible lookup of a language the era does not
    support (`lookupTxInfoResultImpossible`) has no counterpart. The redeemer and the datum of the legacy
    arguments are read from their bytes after the purpose, whose failure is the reference's. The spending
    datum (`AlonzoTxInfo.spendingDatum`) is read before the purpose for V1 and V2 and after it for V3; only a
    spending purpose has one, and its translation cannot fail, so the order shows in no result.
  - A body's outputs are zipped with the transaction indices in each of the three languages, as the reference
    zips them with `[minBound ..] :: [TxIx]`, a `Word16` (`Conway/TxInfo.hs:412-416, 452-456, 498-502` at
    f649f975, `:433-437, 470-474, 513-517` at 9df9f98c and 29a7d656), so an output past index 65,535 is in no
    language's information (`TxIx.enumerate`). `EraPlutusContextSuite` widens a body to 65,537 outputs and
    finds 65,536 in each language's `TxInfo`, the flat translation's the same. The node's other numberings of
    a body's outputs follow the same bound (`TxIn.txouts`, `TxIn.mkCollateralTxIn`, `TxOutsBoundSuite`).
  - `EraPlutusContextSuite`: the typed V1 and V2 `TxInfo`s, through the era's memoised result, equal the flat
    ones or refuse alike on the same 432 rows and the 62 recorded block transactions, at the row's version and
    at 9, 10 and 11, reaching every refusal a V1 or V2 context has in Conway but `PlutusPurposeNotSupported`,
    which the guard on votes and proposals reaches first, as in the reference. Each of the 141 Plutus V1 and
    V2 requests the reference collected there (14 recorded, 78 of the matrix, 49 of the script results) has
    the typed arguments byte for byte. `ConwayPlutusContextRefusalsSuite` compares the V1 and V2 `TxInfo`s on
    the 84 refusal-corpus cases too.
- **The scripts of a transaction with their contexts** (`era/PlutusEvaluate.scala`; `Plutus/Evaluate.hs` and
  `Alonzo/UTxO.hs:431-465` at cardano-ledger f649f975; `Alonzo/Plutus/Evaluate.hs:83-182` and
  `Alonzo/Plutus/TxInfo.hs:102-130` at 9df9f98c, with the shape below): `PlutusRunnable`, `SupportedPlutusRunnable` (equal by language and script hash),
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
    parameters. 29a7d656's `mkPlutusWithContext` converts the cost model to the language's and raises
    `ImpossibleContextError` where that fails (`Alonzo/Plutus/TxInfo.hs:127-128`), which 9df9f98c's does not; a
    model here is of the script's language by its type (`CostModel[script.L]`), so there is no conversion and
    that branch has no counterpart.
  - The collection reads the scripts used from the transaction's information (`ltiScriptsUsed`), and the
    arguments read the spending datum themselves (`toPlutusArgs`, below), where f649f975's collection is given
    the datum by `mkPlutusWithContext`. The datum's failure, which only an inline datum taken unread can reach,
    is the node's own `Undecodable` with the decoder's failure, where the reference raises `error`, as an
    output's inline datum's is in the contexts.
  - `EraPlutusContextSuite`: the scripts collected with their contexts, as the evaluator's requests, equal the
    flat path's (`Phase2.collect`) in order on the same 432 rows and 62 recorded block transactions, or both
    fail with the same failures in the same order, compared after normalisation (a missing redeemer by its
    purpose's tag and index, a translation's failure as the flat one), reaching a missing redeemer, a missing cost model and a
    translation's failure. A language whose tag the models lack gives `NoCostModel`, and an unknown tag is no
    language's model. Each runnable made from a script has that script's hash; the resolver gives a provided script's runnable
    the key it is provided under, which is trusted to be its hash, as the reference trusts it. A needed script's cached runnable is the one used,
    and a script the cache holds that the transaction does not provide meets no need.
  - The collection is of a top-level transaction, with no sub-transaction's information, which only Dijkstra's
    annotated transaction gives (#85's item on Dijkstra's script contexts).
- **The classes at the shape of 9df9f98c and 29a7d656** (`era/PlutusContext.scala`, the `TxInfo` modules and
  `era/PlutusEvaluate.scala`; `Alonzo/Plutus/Context.hs:104-252`, `Alonzo/Plutus/TxInfo.hs`, `Babbage/TxInfo.hs`
  and `Conway/TxInfo.hs` at cardano-ledger 9df9f98c, which 29a7d656, the ledger of cardano-node 11.2.1, shares).
  The owner decided, in the chat session doing the work, to give the shared script-context classes this shape,
  as a Mirror of 29a7d656 and 9df9f98c with no change of behaviour; f649f975's signatures differ (asked
  2026-11-20T04:08:22Z, decided 2026-11-20T04:10:24Z, #85):
  - `LedgerTxInfo` is indexed by the transaction's level and holds the scripts used (`ltiScriptsUsed`) and the
    level's information (`LedgerLevelTxInfo`): at the top level the memoised information of each sub-transaction
    by its id, which only Dijkstra fills, and a sub-transaction's index. The sub-transaction's case is Dijkstra's
    alone, so the level of an era with the top level only is the top level by construction, as the reference's
    `withTopTxLevelOnly` shows it; an era's body class reads the level (`EraTxBody#levelOf`, the reference's
    `HasEraTxLevel LedgerTxInfo`). The script hashes by purpose (`ltiScriptHashesUsed`) are derived from the
    scripts used, as every construction of the reference's makes them (TYP-1).
  - `PlutusTxInfoResult` is the information or its failure, where f649f975's is a function of the purpose that
    every era it has ignores. `toPlutusScriptPurpose` and `toPlutusArgs` take the transaction's information, and
    the arguments read the spending datum themselves (`AlonzoTxInfo.spendingDatum`, its failure still the node's
    own `Undecodable`); the collection reads the scripts used from the information.
  - The unsupported certificate and purpose are `AlonzoContextError`'s, as at 9df9f98c and 29a7d656; Conway's
    injection puts them back as Conway's own, which f649f975's Conway raises itself, so every Conway error is
    the same value at both. Conway's shared translations are generic in the era over Conway's classes, as the
    reference's are, and Alonzo's `transPlutusPurpose` replaces Conway's `transPlutusPurposeV1V2`.
  - `EraPlutusContextSuite` and `ConwayPlutusContextRefusalsSuite` pass with their expected values unchanged: every
    Conway `TxInfo`, argument, collection and refusal above is the same. Tests show Conway's injection of the two
    cases, a vote refused by Alonzo's purposes, the script hashes derived from the scripts used, and that a
    sub-transaction's information at Conway does not compile, its twin at Dijkstra compiling.
  - Three differ in structure from the reference: the derived script hashes, the sub-transaction's case of
    Dijkstra alone, and the level read from the level's information. Dijkstra's script contexts read the level at
    both levels, and V4's translation the script hashes, against the reference (below): the 273 contexts recorded
    on the testnet's chain, 18 of them in sub-transactions. The scripts used come from the scripts needed, one per
    purpose's index, so no two share an index and `Map.fromList`'s choice of the last of a repeated one shows
    nowhere.
- **The Plutus V4 script context's types** (`era/PlutusLedgerApiV4.scala`; `plutus-ledger-api/src/PlutusLedgerApi/V4/`
  at plutus 261766ad, plutus-ledger-api 1.71.0.0, the version the Leios testnet's node links): `PV4`, the types the
  ledger's Plutus V4 translation builds (the transaction's information with its sub-transaction's index, direct
  deposits, account balance intervals and guards, the script context with its script information, a guard's
  information of the whole transaction, `TopTxInfo` and `TopTxInfoSimplified`, and V4's certificates, purposes,
  outputs, committee and governance actions), each with its `ToData` encoding: a record of `makeIsDataSchemaAsList`
  and a newtype derived `via ListEncoded` as the list of its fields, a sum of `makeIsDataSchemaIndexed` as the
  constructor of its index; a type V4 takes from V1 to V3 is that version's. A newtype over V3's record or a tuple
  that a pattern synonym presents as fields (`TxOutRef`, `GovernanceActionId`, `ProtocolVersion`,
  `ProposalProcedure`) has those fields here, a difference of structure whose encoding the suite compares;
  `Committee` keeps its pair. The language families' Plutus V4 cases and `PlutusV4Args` are V4's types, as at
  9df9f98c (`Alonzo/Plutus/Context.hs:264-289`, `Plutus/Language.hs:583`) and 29a7d656, where f649f975's are V3's.
  - plutus-tx's ratio (`PV3.Rational`, which V4's wraps) is made only by `ratio` and `fromHaskellRatio`
    (`plutus-tx/src/PlutusTx/Ratio.hs:216-225, 389-390`, plutus 261766ad), so it is reduced with a positive
    denominator, the invariants the reference keeps (`:68-73`); `transBoundedRational` uses `fromHaskellRatio`, as
    9df9f98c's does (`Plutus/TxInfo.hs:119-120`).
  - Direct tests write values the corpus cannot tell apart in the reference's field order: a minting purpose with
    distinct hash and policy, a committee, `TopTxInfoSimplified` with every field distinct, and the V3 voters and
    vote the corpus lacks; and a Plutus V4 script's arguments, through its language, are its context alone, each
    recorded argument's bytes.
  - `PlutusLedgerApiV4Suite` reads each of the 53 Plutus V4 arguments the reference recorded (34 on the testnet's
    chain, 14 of the hand-written cases, 5 of the refusals), at both levels, into the types, refusing any encoding
    it does not expect, and writes each back to the reference's bytes; every script purpose is among them, and a
    guard both with the whole transaction's information and without.
  - The ledger's translation builds each of the 53 from the transaction it was recorded for, its serialized
    arguments compared byte for byte (Dijkstra's Plutus V4 script contexts, below).
- **Dijkstra's Plutus V1 to V3 script contexts** (`era/dijkstra/DijkstraTxInfo.scala`;
  `eras/dijkstra/impl/src/Cardano/Ledger/Dijkstra/TxInfo.hs:333-569`, cardano-ledger 9df9f98c): Dijkstra's
  instances of `EraPlutusTxInfo` for Plutus V1, V2 and V3 and of `ConwayEraPlutusTxInfo` for V3, on Conway's,
  Babbage's and Alonzo's translations, which are generic in the era (above), with Dijkstra's own
  `transTxCertV1V2` (`:370-390`: a registration and an unregistration, without their deposit and refund, a
  delegation to a pool alone, and a pool's registration and retirement in the V1 form, every other certificate
  refused, as Dijkstra has no certificate of Shelley's form to fall back on), `transTxCertV3`
  (`:534-566`: a deposit and a refund always given), `guardDijkstraFeaturesForPlutusV1toV3` (`:486-519`: direct
  deposits, account balance intervals, required top-level guards and guards that are scripts refused, in that
  order) and `transFailUnsupportedScriptInSubTx` (`:521-532`).
  - Each instance takes the level from the transaction's information (`LedgerTxInfo.withBothTxLevels`, the
    reference's `withBothTxLevels` of the transaction, `Core/TxLevel.hs:123-130`): at a sub-transaction a V1 to
    V3 language's information is its refusal. V1 and V2 run Conway's guard, then Dijkstra's; V3 runs Dijkstra's
    alone and refuses a reference input that is an input at every version, where Conway's refuses it from
    version 11. A refusal is nested as the reference's `Inject` instances nest it: Alonzo's through Babbage's
    and Conway's errors, Conway's through Dijkstra's, Dijkstra's own unwrapped. A body's outputs are translated
    with their indices as the reference zips them with `[minBound ..] :: [TxIx]` (`:346-350, 406-410,
    450-454`), so none past index 65,535.
  - Their evidence is `DijkstraPlutusContextSuite`'s, with Plutus V4's (below).
- **Dijkstra's Plutus V4 script contexts, its `EraPlutusContext` and the annotated transaction's makers**
  (`era/dijkstra/DijkstraTxInfo.scala`, `DijkstraStAnnTx.scala`; `eras/dijkstra/impl/src/Cardano/Ledger/Dijkstra/TxInfo.hs:299-331,
  571-1058` and `Dijkstra.hs:111-219`, cardano-ledger 9df9f98c): Dijkstra's instances of `EraPlutusTxInfo` and
  `ConwayEraPlutusTxInfo` for Plutus V4, with its translations of inputs, outputs (a pointer address refused as
  `PointerPresentInOutput`), certificates by `AccountId`, purposes with the hash of the script used for them
  (`ScriptHashNotFoundForPurpose` first), withdrawals, direct deposits, account balance intervals, guards,
  required top-level guards, votes, proposals and governance actions; a guard's whole-transaction view at the top
  level (`transGuardingTopTxInfo`: each sub-transaction's memoised V4 information, its failure wrapped as
  `SubTxContextError`, and the batch, sub-transactions first and the top level last, combined by the reference's
  semigroups); the production `EraPlutusContext[Dijkstra]` with its memoised `DijkstraTxInfoResult`; and
  `mkDijkstraStAnnTopTx` and `mkDijkstraStAnnSubTx`, which annotate each sub-transaction at its index and hold its
  memoised information in its top level's.
  - A V4 context has no guard of the features a V1 to V3 context cannot show, and its information is the same at
    both levels but for the sub-transaction's index. A missing memoised information of a sub-transaction, which the
    makers rule out, is `ImpossibleContextError` with a typed reason (`ImpossibleContext.MissingTxInfoResult`)
    where the reference's carries a `Text` message; Scarab encodes no context error of
    the era types, so it has no wire form.
  - `DijkstraPlutusContextSuite` annotates each transaction with the makers, at the fixture's protocol version,
    clock and cost models, and compares each level's collection with the reference's record: its contexts in
    order (each one's serialized arguments byte for byte, and the rest of its `PlutusWithContext` record, the
    protocol version, language, script, units and cost model, field by field), or its failure as the
    typed `CollectError`s, every constructor and field of which, the networks, credential kinds, votes, anchors,
    actions, purposes and indices among them, is what the reference's record (`Show`) reads as. The chain context
    fixture: all 273 contexts of its 200 transactions (68 Plutus V1, 54 V2, 117 V3 and 34 V4, 18 of the V4 ones in
    sub-transactions). The refusals fixture: every level of its 22 cases, 13 contexts and 12 refusals (Dijkstra's
    guard of each of its four fields, Conway's guard of V1 and V2 on each of its four, a DRep's registration under
    V1, a V3 script in a sub-transaction, a guard whose sub-transaction fails and a guard's redeemer with no
    script); its contexts include four guards whose whole-transaction view combines the levels' votes and current treasury
    amounts: one voter's votes on an action at three levels (the right-biased union of `VotingProcedures`, the
    sub-transactions' in order and then the top level's), votes by other voters and on other actions at two levels,
    a treasury amount given by a sub-transaction only, and amounts at two levels (the first present; they differ,
    which the rules refuse and the collection does not check). Making the union left-biased, or taking the last
    amount, fails the suite on the first and the last of these. The hand-written V4 cases (`leios-testnet-contexts`): every level of its 11 cases, 14 contexts and 7
    refusals (a pointer and a Byron output, an unknown input, a reference input that is an input, a redeemer that
    names nothing, a script without its redeemer, a key withdrawal's redeemer). These are the 53 Plutus V4
    arguments `PlutusLedgerApiV4Suite` reads. No case is deferred. A body of 65,537 outputs has 65,536 in each
    language's information, V4's included.
  - The makers defer each level's collection until it is read and keep it (`Eval.later`), as the reference's
    annotated transaction keeps its fields lazy (`Tx.hs:535-557`) and reads the batch's collections after phase 1,
    the top level's first (`Dijkstra/UTxO.hs:258-276`): the suite shows that annotating the guarded case and reading
    its other members runs no translation and reads no clock.
  - Not yet: the node builds its contexts with the flat path; the rules on the era types (UTXOS, LEDGER and the
    mempool) come with the rest of stage 2 (#85).
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
  - Not yet (#85): LEDGER and the mempool on the era types (UTXOW's transition came with UTXOW whole, below).
    The node still builds contexts and checks transactions with the flat rules.
- **UTXOW whole, with the reference's failures** (`era/rules/Utxow.scala`, `era/rules/UtxowFailures.scala`,
  `era/AllegraTx.scala`; the `Rules/Utxow.hs` of Shelley, Alonzo, Babbage and Conway, `Allegra/Scripts.hs:
  477-497` and `Allegra/Tx.hs:104-110`, cardano-ledger f649f975, where 29a7d656 and 9df9f98c differ in derived
  `Ord` instances):
  - UTXOW's failures are the reference's own types, one per era (`ShelleyUtxowPredFailure` to
    `ConwayUtxowPredFailure`), with `EraRuleFailure "UTXOW"` a match type with Conway's case, and its events
    Shelley's and Alonzo's (`AlonzoUtxowEvent`). Each predicate above reports its own era's type, a missing
    redeemer's purpose as its item, as the reference's; Conway's injections are `babbageToConwayUtxowPredFailure`,
    `alonzoToConwayUtxowPredFailure` and `shelleyToConwayUtxowPredFailure` (`InjectUtxowFailure`). The MIR
    quorum's failure, which the reference's injection into Conway calls impossible (`error`), has no value at
    Conway (`MirSigners`), as PPUP's has none (`absurdEraRule`): the case is impossible at Conway in both, and
    the compiler enforces what the reference asserts with `error`. The failures of UTXO's predicates and of
    UTXOS are injected into UTXOW's as `UtxoFailure . injectFailure` (`Conway/Rules/Utxow.hs:129-145, 318`),
    one instance over UTXO's injection, Shelley's by the build's (`conwayShelleyUtxo`).
  - A native script is evaluated on the era types: `EraTx#validateNativeScript` (`Core.hs:213`), Allegra's
    `validateTimelock` and `evalTimelock` from Allegra to Conway, over the representation they share, and
    Dijkstra's `validateDijkstraNativeScript` (`Dijkstra/Tx.hs:427-438`, cardano-ledger 9df9f98c).
    `evalTimelock` evaluates the scripts the reference evaluates, in its order, and stops where it stops: all of a
    list at the first that fails, any at the first that holds, `m` of a list once `m` hold, none for a
    non-positive `m` (`isValidMOf`, `Allegra/Scripts.hs:487-495`). It is deferred and run as a trampoline
    (`Eval`), so a script as deep as a transaction is evaluated on the heap. `validateFailedBabbageScripts` reads
    it.
  - `babbageUtxowTransition` (`Babbage/Rules/Utxow.hs:308-390`): the failing needed native scripts, the
    missing and extraneous scripts, the datums, the redeemers, the signatures, the witnesses needed, the
    auxiliary data, the well-formed scripts and the script integrity hash, every failure recorded and injected;
    the signatures, the auxiliary data and the well-formed scripts only under the policy that validates the
    signal; then UTXO as its sub-rule, its failures and events embedded as the reference's
    `Embed (UTXO era) (UTXOW era)` does (`EmbedUtxoInUtxow`). It reads the UTxO set as UTXO does. A signature
    check or a script decoding with no verdict is raised as the node's `LedgerDefect`, as the flat rule raises
    it.
  - `ConwayUtxowPredFailure.toStored` gives a failure's flat form, a missing redeemer's purpose, of UTXOW's
    `MissingRedeemers` or UTXOS's `NoRedeemer`, given its flat form, with its index and spent output, from the
    transaction's scripts needed (`storedPurposes`). A context's failure to translate is left as it is.
  - Evidence:
    - `AllegraTimelockSuite`: `evalTimelock` gives the flat evaluation's verdict on every form, each bound
      unbounded, inside and outside, and `m` of a list at, below and above the scripts that hold, zero and
      negative among them; it asks about the key hashes the reference asks about, in its order, and no others
      (a probe of the signers' set), on each of the lists' stopping points, nested and not; on a small stack, a
      script nesting all, any and one of a list in turn 6,000 levels deep; `validateNativeScript` on the preprod
      script of 5,383 levels, on a small stack; and `validateFailedBabbageScripts` on that transaction against the
      flat check, with a needed script that no witness signs (`ScriptWitnessNotValidatingUTXOW`), one that a
      witness signs, and the failing one provided but not needed: no Conway case of the corpora has a failing
      native script.
    - `EraUtxoSuite`: the whole UTXOW and the flat `rules.Utxow` on the 70 submissions, as submitted and with
      the flag the other way, under each build and each policy: the same failures in order, the same state where
      admitted, and the counters UTXOS's events give through UTXO's and UTXOW's embeddings. As submitted, each
      verdict is the reference's. With each admitted case's script integrity hash taken out, UTXOW's failures
      alone are recorded, none of UTXO's, and UTXOS evaluates no script (an evaluator that fails the suite if
      asked). The injections of UTXO's and UTXOS's failures into UTXOW's equal the embedding's.
    - `ConwayUtxoChecksSuite`: the same on every Conway case of the refusal corpora, UTXOW's own checks refusing
      some; where the reference refused a transaction for UTXOW and UTXO alone, the whole rule's failures, two
      levels below the root as under MEMPOOL and LEDGER, encode to the reference's refusal byte for byte, the
      mempool measure's three refusals left out by name as for UTXO.
    - `RedeemerIndexReferenceSuite`: on the three Conway cases of the reference's recorded verdicts on a
      certificate's redeemer index (`fixtures/devnet/redeemer-index.jsonl`, #1203), the accepted one is
      admitted, and each refused one's failures, missing redeemers and UTXOS's collection errors among them,
      encode to the reference's refusal byte for byte.
    - `DijkstraNativeScriptEvalSuite`: Dijkstra's `validateNativeScript` on every transaction of the Leios
      corpus with guards and key witnesses, and each again with a key guard added to its body (the corpus's
      guards are scripts'), with a guard of each of its body's credentials, one it lacks, its witness's
      signature, a validity start and end at its interval's bound, and two of three of these and three of
      three: the transaction's witnesses, interval and guards read as the reference's
      `validateDijkstraNativeScript` reads them, the verdict `evalDijkstraNativeScript`'s on the flat
      transaction's.
    - The existing suites of the predicates (`ConwayUtxowChecksSuite`, `EraUtxowSuite`,
      `EraPlutusContextSuite`) keep their expected values, each failure injected into Conway's and put in the
      flat form.
  - Incomplete (CNF-2): a context's failure to translate (`BadTranslation`) is compared by its kind alone, its
    contents being `EraPlutusContextSuite`'s, while a missing redeemer is compared by its purpose in the flat
    form, one with none failing the comparison, and a missing script by its hash; the refusals are compared
    through the flat form and the node's encoder (#796); the redeemer-index cases of Alonzo and Babbage are of
    eras whose UTXOW is not typed yet.
  - Not yet (#85): LEDGER and the mempool on the era types, and the node's phase-2 counters (`PlutusStats`).
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
- **GOV whole, with the reference's failures** (`era/Proposals.scala`, `era/conway/ConwayProposals.scala`,
  `era/rules/Gov.scala`, `era/rules/GovFailures.scala`; `Conway/Rules/Gov.hs`, `Governance/Proposals.hs` and the
  voting functions of `Governance/Internal.hs`, cardano-ledger f649f975, where 29a7d656 differs only in the failure
  type's derived `Ord`):
  - Conway's `conwayGovTransition`, generic in its era over `ConwayEraPParams`, `ConwayEraCertState` and
    `ConwayEraTxCert`: each proposal's checks in the reference's order (`checkBootstrapProposal`, the hard fork's
    `preceedingHardFork` and `pvCanFollow`, `actionWellFormed`, the return and withdrawal accounts after the
    bootstrap phase, the deposit, the networks, the guardrails script hash, a withdrawal's sum, a committee
    update's members) and its ancestry (`proposalsAddAction`, `InvalidPrevGovActionId`); then the voters that do
    not exist, the actions that do not, `checkBootstrapVotes`, `checkVotesAreNotForExpiredActions` and
    `checkVotersAreValid`, the votes cast and the votes of the DReps the transaction unregisters removed. Both
    events are told and the proposals after are the state whatever was recorded. An expiry is `addEpochInterval`'s
    `Word64` sum, and `pvCanFollow`'s minor version a `Word32` sum, both wrapping. A proposal's index is a
    `GovActionIx` (`Word16`, `indexedGovProps`); a 65,537th proposal has none, where the reference's `succ` of
    `maxBound` throws, and is a raised `GovDefect.ProposalIndexOverflow` before any proposal is judged
    (`EraProposalsSuite` checks the index 65,535 and its successor). An account in a failure is the reference's
    `AccountAddress`, and the events' transaction id the hash of its body (`Hash32`).
  - The state is the reference's `Proposals era`: an ordered map of `GovActionState era` and the four purpose trees
    (`GovRelation`, `PRoot`, `PEdges`, `PGraph`), made only by `proposalsAddAction`, which adjusts a parent's
    children before it inserts the new edge, as the reference composes them. Conway's is read from the node's
    record of the proposals (`ConwayProposals.fromStored`, the reference's `mkProposals` over its roots and
    actions) and written back (`toStored`), so nothing persisted changes. A record the era cannot read is a typed
    `ProposalsReadError`, never a verdict: an action that attaches to no tree (`Unattached`), or a store rebuilt
    with other actions than the record, as an id held twice gives (`Malformed`, the reference's `OMap is
    malformed`), never one action dropped silently. `EraProposalsSuite` checks a chain's root, parent and child
    edges directly, which `toStored` does not keep, an action replacing its own id named as its parent, and both
    read errors. The read makes Conway's proposals from the proposals the decoder stored,
    the one way besides the decoder that a `ProposalProcedure` is made.
  - The failures are `ConwayGovPredFailure`, its 19 constructors in tag order, and the events `ConwayGovEvent`;
    `EraRuleFailure "GOV"` and `EraRuleEvent "GOV"` are match types with Conway's case, and `InjectGov` is the
    reference's two injections at Conway, the identities. A mismatch is the node's `Mismatch`, without the
    reference's relation marker (`RelEQ`, `RelGT`), which names the comparison only in the reference's rendering. The embedding into LEDGER (`ConwayGovFailure`, `GovEvent`)
    comes with LEDGER. `ConwayGovPredFailure.toStored` gives a failure's flat form, one-way glue the comparisons
    read; an action is put in it as the transaction's proposal read it (`readOf`).
  - Who may vote (`ConwayVoting`) is the reference's `isVotingAllowed` of each voter's threshold, written as the
    constructors whose threshold is `NoVotingAllowed`: the thresholds' values are ratification's and never reach it,
    and with no committee, as the reference passes it, the committee's threshold of the other actions is
    `NoVotingThreshold`. On the recorded proposals of each of the seven actions and on two crafted parameter
    changes, one of the security group and one of none, it equals the flat rule's (`EraConwaySuite`).
  - `ConwayGovChecksSuite` runs the whole rule and the flat GOV on every Conway case of the refusal corpora, at the
    recorded version and at 9, 10 and 11: the same failures in the same order, or the same proposals after. These
    runs reach every one of the 19 failures. GOV's events carry the transaction's id, the proposals after and the
    DReps its certificates unregister. Where the reference refused a transaction for GOV alone (29 cases), the
    whole rule's failures, run two levels down as under MEMPOOL and LEDGER, encode to the reference's refusal byte
    for byte. A crafted committee update that fails both its conflict and its expiry records them in that order. The
    predicates' comparisons keep their expected values, each failure in the flat form.
  - `UpstreamGovernanceFunctionalSuite` runs it on the upstream GOV cases: the 15 lawful seeds, the empty vote maps
    of a named committee voter and the registered DRep's vote leave the reference's proposals, as the flat rule
    does, and the changed deposit is refused with the reference's failure. Five seeds (510102, 510103, 510109,
    510113 and 510114) hold a zero registration count in their pools' VRF key hashes, which the node's decoder
    refuses; GOV never reads them, so those seeds run on their original environment with that field left out.
    Cast again on the proposals after, the DRep's vote replaces the one cast, and the event names it.
    `UpstreamGovConformanceSuite`'s generated empty signal leaves the reference's proposals too.
  - Where the flat rule differs: it lists the failures over votes (`GovActionsDoNotExist`, `VotingOnExpiredGovAction`,
    `DisallowedVoters`, `DisallowedVotesDuringBootstrap`) in the order the body's maps arrived in, and
    `TreasuryWithdrawalReturnAccountsDoNotExist` in the order the withdrawals did. The reference folds a `Map`
    (`foldrVotingProcedures`, `Map.keys`), so its lists ascend, and so do the era rule's. A crafted body with two
    votes on unknown actions out of order shows it (`ConwayGovChecksSuite`). The verdict is the same; only the
    order of a refusal's list differs, and no recorded refusal holds an out-of-order body, so the evidence for the
    reference's order is its source. Tracked as #85's
    [item](https://github.com/KD5UJC-XYZ/scarab/issues/85#issuecomment-7054122427).
  - Incomplete (CNF-2):
    - the environment's certificate state is the recorded state's here; LEDGER gives GOV the state after CERTS
      (LEDGER whole, below);
    - every refusal GOV alone gave holds one failure, so the byte-for-byte comparison does not tell the depth's
      order of several; the cases GOV refused with other rules (`certs-gov-utxow`, `gov-proposal-wrong-deposit`)
      are compared through LEDGER (below);
    - the scenarios of `ConwayGovernanceSuite`, `PreviewConwayRulesSuite` and the mempool suites run GOV through
      the flat LEDGER (LEDGER whole, below, says why);
    - `ProposalsReadError` is reached by crafted stores only (`EraProposalsSuite`): the node's record is written
      by the flat rule.
  - Not yet (#85): the governance state's classes (`EraGov`, `ConwayEraGov`) over typed parameters; the committee in
    the environment as the reference's `Committee era`; the votes keyed by the reference's typed voter. The ordered
    map is a vector, and the proposals are read per transaction, which the Conway switch must make cheap, as the
    flat rule's single pass over the votes is (#94). The flat rule numbers proposals with an unbounded index
    (`zipWithIndex`), where the reference's is a `Word16`; only a 65,537th proposal tells them apart, and stage 3
    deletes the flat rule.
- **LEDGER and LEDGERS whole, with the reference's failures** (`era/rules/Ledger.scala`,
  `era/rules/LedgerFailures.scala`; `Conway/Rules/Ledger.hs`, `Conway/Rules/Ledgers.hs` and Shelley's
  `Rules/Ledger.hs` and `Rules/Ledgers.hs`, cardano-ledger f649f975, where 29a7d656 and 9df9f98c differ in derived
  `Ord` instances, the withdrawals' move below and Shelley's `AccountAlreadyRegistered` injected through DELEG,
  below):
  - Conway's `conwayLedgerTransition`, generic in its era over the reference's classes: for a transaction whose
    scripts pass, the treasury value, the reference scripts' size, the delegation of the key accounts withdrawn
    from after the bootstrap phase (from the certificate state before the certificates), the withdrawals' test,
    the DReps' expiries and the accounts drained where the build moves them to LEDGER (`WithdrawalsRule`, from the
    judgment's `Globals`), CERTS, then GOV with the certificate state after CERTS; for every transaction UTXOW, with
    the certificate state before the transaction in its environment. Each sub-rule is nested, its failures and
    events embedded (`EmbedInLedger`). GOV's proposals are the node's record read into the era's and written back
    (`ProposalsRecord`), a record the era cannot read a raised `ProposalsReadError`. `ShelleyLedgers.ledgersTransition`
    annotates each transaction against the UTxO set it is judged on and nests LEDGER with its `TxIx`. Where the
    environment has no epoch, it is the slot's by the total `epochOf` of `Globals`' geometry, where the reference's
    `epochFromSlot` (`Slot.hs:66-69`) reads its `Globals`' epoch information and fails outside a bounded history.
  - The failures are `ConwayLedgerPredFailure`, its nine constructors in tag order, Shelley's
    `ShelleyLedgerPredFailure` with DELEGS's failure having no value at Conway (`DelegsPredFailure`), where the
    reference's injection calls it impossible, and `ShelleyLedgersPredFailure`; the events `ConwayLedgerEvent` and
    `ShelleyLedgersEvent`. The withdrawals are the reference's `Withdrawals` and `NonEmptyMap AccountAddress`.
    `ConwayMempoolFailure` holds the node's typed cause (`MempoolProblem`) where the reference holds its `Text`, as
    the flat failure does: LEDGER never gives it. `InjectLedgerFailure` is the reference's
    instances of every rule below LEDGER, Shelley's UTXO failure's by the build's injection
    (`conwayShelleyUtxo`), and `ConwayLedgerPredFailure.toStored` the flat form the comparisons read.
    `InjectLedgersFailure` is the reference's instances into LEDGERS (`Conway/Rules/Ledgers.hs:28-82`), each
    `LedgerFailure` over LEDGER's, Shelley's LEDGER failure without one as in the reference; `LedgerInjectionSuite`
    summons each injection into LEDGER and LEDGERS and finds the failure the embeddings wrap it in. Under
    29a7d656 and 9df9f98c Shelley's `AccountAlreadyRegistered` goes into LEDGER as `ConwayCertsFailure .
    CertFailure . DelegFailure` (`Conway/Rules/Ledger.hs:137-138` at both), a function of the build
    (`InjectLedgerFailure.conwayAccountAlreadyRegistered`, none under the 11.1 line, whose f649f975 has neither the
    type nor the instance), and into LEDGERS under none; the suite checks its wrapping under each build.
  - `ConwayLedgerChecksSuite` runs the era LEDGER and the flat `rules.Ledger` from the state the mempool judges a
    transaction in, on every Conway case of the refusal corpora, at the recorded version and at 9, 10 and 11, under
    each build and policy: the same failures in the same order, cases of three and more among them, or the same
    ledger state. The one named difference is the flat rules' withdrawals under 11.2 and Leios below version 11
    (CERTS's bullet below), where the era rule gives the reference's LEDGER failure: on
    `withdrawal-more-than-balance`, `withdrawal-unregistered-account` and `withdrawal-network-flag-true`, at each
    version below 11 under those builds and each policy, and on no other case, the flat failures in their order with CERTS's refusal of the withdrawal
    replaced in its place by LEDGER's, the account and amount withdrawn, an incomplete one's against the chain's
    balance. Where the reference refused a
    transaction, every Conway case but the four MEMPOOL refuses itself or by its measure, named, the era LEDGER's
    failures, one level below MEMPOOL, encode to the reference's refusal byte for byte: the order of failures across
    LEDGER's, CERTS's, GOV's, UTXOW's and UTXO's depths, `certs-gov-utxow` and `gov-proposal-wrong-deposit` among
    them. LEDGERS of one transaction is its LEDGER embedded, on every case. A crafted DRep that unregisters and votes
    in one transaction is refused by GOV as a voter that does not exist, the state after CERTS, which GOV alone on
    the state before admits.
  - The upstream LEDGER and LEDGERS judgments (`UpstreamNativeLedgerConformanceSuite`,
    `UpstreamMultiLedgerConformanceSuite`, `UpstreamFreshLedgerConformanceSuite`) run the era LEDGER and LEDGERS on
    the fixtures' inputs too, each transaction read by Conway's decoder from its original parts, the root of its
    judgment, under an epoch information of one era without an end, as the fixtures record no era history: the 4
    native LEDGER and LEDGERS judgments, the 4 multi-transaction batches and the 46 generated LEDGER and LEDGERS
    judgments, the original generated-cache refusals among them, leave the reference's full post-state, or
    refuse with failures that encode to the reference's bytes in their order, a collection's translation
    failures among them (`EraLedgerRun.flat`). On two generated transactions LEDGER tells the pool its CERTS
    registers and the DRep whose votes its GOV removes, those the reference's post-state registers and drops,
    wrapped as LEDGER embeds them.
  - The reference-accepted submissions run through the era LEDGER too, from the chain each suite builds:
    `EraUtxoSuite`'s 70 Conway submissions, as submitted with the reference's verdict, and each with its flag
    flipped under each build and policy, give the flat LEDGER's failures in order or its state;
    `RedeemerIndexReferenceSuite`'s accepted Conway case leaves the flat LEDGER's state, and its refused ones, one
    level below the root, encode to the reference's refusal; `PastHorizonReferenceSuite`'s Conway case, from the
    state the mempool judges it in and the clock of the cluster's era history, refuses with the reference's bytes,
    the translation past the horizon among them. The counters the scripts' events give through LEDGER's
    embedding (`EraLedgerRun.counters`: the passing and failing transactions and the scripts run) are the flat
    rule's `Counted.plutus` on every admitted case there and in `ConwayLedgerChecksSuite`, a passing and a failing
    run of scripts among them; the integrity checks the flat rules count are not compared, as no event tells
    them. `ContextRefusalReasonSuite`'s 12 refused context submissions, one level below the root, encode through
    the era LEDGER to the reasons the reference recorded byte for byte, each translation failure with its contents,
    and `EraUtxoSuite` compares each collection's failures with the flat rule's with their contents too
    (`EraLedgerRun.flat`, which projects only the translation failures it encodes faithfully, a time past the
    horizon only by the clock's own failure).
  - `PreviewConwayRulesSuite` runs its recorded Preview Conway transactions through the era LEDGERS too, each read
    by Conway's decoder from the bytes the flat transaction keeps, from the state built for the flat rules: every
    proposal, spending and committee vote transaction is admitted with the flat rules' ledger state and counters
    (the integrity checks the flat rules count aside, which no event tells), GOV's `GovNewProposals`, wrapped by
    LEDGER and LEDGERS, tells the proposals the state then holds, and another guardrails script is refused by
    GOV alone, as the flat rules refuse it.
  - Incomplete (CNF-2): `ConwayGovernanceSuite` and the mempool suites build flat transactions without bytes, which
    the era types cannot read until the test builders move to per-era kits (§4), so they run the flat LEDGER only
    (https://github.com/KD5UJC-XYZ/scarab/issues/85#issuecomment-7056445357); LEDGERS is compared on one
    transaction per case and on the upstream batches, not yet on a recorded block's transactions in sequence
    (https://github.com/KD5UJC-XYZ/scarab/issues/85#issuecomment-7056445970); the epoch from the slot is compared
    within an era history only, as every suite's slot is in its history, not where the reference's
    `epochFromSlot` fails.
  - Not yet (#85): the node's mempool, block body and phase-2 counters run the flat LEDGER until the Conway switch.
- **Conway's certificate rules** (`ledger/.../era/rules/Certs.scala`, `era/rules/CertsFailures.scala`): CERTS,
  CERT, DELEG, GOVCERT and Shelley's POOL, each the reference's transition under its name, generic in its era
  over the state classes `EraAccounts`, `ConwayEraAccounts`, `EraCertState` and `ConwayEraCertState` (Conway's
  instances over the records the node keeps).
  - Their failures are the reference's own types (`ConwayCertsPredFailure`, `ConwayCertPredFailure`,
    `ConwayDelegPredFailure`, `ShelleyPoolPredFailure` and `ConwayGovCertPredFailure`, cardano-ledger f649f975,
    each in tag order), and their events `ConwayCertsEvent`, `ConwayCertEvent` and POOL's `PoolEvent`, DELEG's
    and GOVCERT's void. `EraRuleFailure` and `EraRuleEvent` of each rule are match types with Conway's case,
    POOL's Shelley's. Each rule reports its failures through the reference's injection (`InjectDeleg`,
    `InjectPool`, `InjectGovCert`, the identities at Conway; `InjectCertFailure` and `InjectCertsFailure` for
    the failures below CERT and CERTS), and a sub-rule's failures and events are embedded as the reference's
    `Embed` instances do (`EmbedInCert`, `EmbedCertInCerts`). POOL tells `RegisterPool` and `ReregisterPool`
    where the reference does. A value outside the state's vocabulary, and a pool's parameters of another layout,
    stay raised defects (`LedgerDefect`). `ConwayCertsPredFailure.toStored` gives a failure's flat form, one-way
    glue the comparisons read. At 29a7d656 and 9df9f98c `StakeKeyRegisteredDELEG` is
    `DelegAccountAlreadyRegistered`, a newtype of the same credential with the same tag and bytes, so one
    constructor serves both.
  - Which rule checks a transaction's withdrawals, and updates its DReps' expiries, depends on the build
    (`WithdrawalsRule`, derived from the build the judgment's `Globals` name, the source POOL reads its layout
    from, so no caller pairs one build's globals with another's rule): at 11.1 CERTS's base case
    below protocol version 11 (`WithdrawalsNotInRewardsCERTS`) and LEDGER from 11
    (`hardforkConwayMoveWithdrawalsAndDRepChecksToLedgerRule`, `Conway/Era.hs:283-284`); at 29a7d656 and 9df9f98c
    LEDGER at every version, the base case gone (`Conway/Rules/Certs.hs:220` at both) and the constructor with
    it. Crafted withdrawals at 9, 10 and 11, under each of the three builds through CERTS's entry point, show the
    base case running only under 11.1 below 11 (`ConwayCertsChecksSuite`). The failure's withdrawals are the
    reference's `Withdrawals`, keyed by `AccountAddress` (`Address.hs:980`), each body account read into it.
  - On the 33 upstream judgments of the 11.1 conformance export (1 CERT, 1 CERTS, 5 DELEG, 5 POOL and 21
    GOVCERT), the era rules, from the same inputs, leave the reference's full post-state
    (`UpstreamCertConformanceSuite`, `UpstreamCertsCoverageSuite`, `UpstreamDelegConformanceSuite`,
    `UpstreamPoolConformanceSuite`, `UpstreamGovCertConformanceSuite`).
  - Where the reference refused a transaction for the certificate rules alone (19 cases, three of them with two or three
    failures), CERTS's failures, run two levels down as under MEMPOOL and LEDGER, encode to the reference's
    refusal byte for byte, the order of failures across the recursion's depths among them
    (`ConwayCertsChecksSuite`). A crafted registration and re-registration of one pool tell `RegisterPool` and
    `ReregisterPool` in order, embedded in CERT's and CERTS's events.
  - Not yet (#85): the flat rules gate the withdrawals at version 11 under every build, so under the 11.2 and
    Leios builds at versions 9 and 10 they refuse a withdrawal that does not drain its account with
    `WithdrawalsNotInRewardsCERTS` in CERTS, where the reference refuses it with
    `ConwayWithdrawalsMissingAccounts` or `ConwayIncompleteWithdrawals` in LEDGER: the same verdict and state,
    other refusal bytes ([item](https://github.com/KD5UJC-XYZ/scarab/issues/85#issuecomment-7055304484); a test
    shows the flat rule's form). None of the 19 certificate-only refusals above was recorded by 11.2.1 or the
    Leios build; the one Leios recording is a refusal of UTXO's. LEDGER's embedding of CERTS comes with
    LEDGER.
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
    adds (above). One more gap is on #85: the committee and its proposals as the node's records in the
    environments.
  - A pool's parameters are the state's `StakePoolParams` (`ledger/.../state/StakePool.scala`; `State/StakePool.hs:424-435`
    at f649f975, `:476-488` at 9df9f98c), era-independent as the reference keeps them in its core library: a
    `KeyHash[StakePool]`, a `VRFVerKeyHash`, `Coin`s, a `UnitInterval`, an `AccountAddress` (`Address.hs:183-191`
    at f649f975) and the owners as `KeyHash[Staking]`s. The certificates hold them and so does `PState`'s
    `future` map, so POOL uses them as they are, with no conversion into the state's vocabulary. `Network` and
    `UnitInterval` moved into the state's vocabulary for them, the era's names aliases of the state's. The
    Leios build's voting key (`sppBlsKey`) is `PoolParamsBlsKey`, by the state's layout: #1063's per-layout
    typing applied to one field, so a value of the 11.1 layout cannot hold a key. Conway's decoders take the
    layout (`ConwayTxBody.decode(raw, layout)`), which gives the key's place: none at 11.1, empty in the Leios
    layout, as a Conway body is read at version 9 and the key only from 12. Its phantom era is not kept. The
    relays and the metadata stay the bytes they were read from, as `StakePoolState` and the state codecs keep
    them, the metadata's hash read with them; decoding them as the reference's types is tracked on #85
    ([item](https://github.com/KD5UJC-XYZ/scarab/issues/85#issuecomment-6999387793)).
  - Writing: the future map is written by the node's parameters' codec, as before, so the native snapshot's
    state bytes do not change. `FuturePoolParamsCodecSuite` checks, in both layouts, that a non-empty future map
    writes the bytes the codec of `PState` wrote while it held the node's parameters, and reads them back.
  - Reading is narrower than before, by the owner's decision of 2026-11-19 (asked 19:18:13Z, decided 20:18:03Z,
    #85 item 38) that the state read matches the reference. The earlier reader kept any metadata term and any
    network nibble of the account's header. The future map's metadata is now read as `DecCBOR PoolMetadata` at the
    state's version (`State/StakePool.hs:522-524` at f649f975, `:683-685` at 9df9f98c): a record of a `Url` and a
    byte string, the `Url` at most 64 bytes of UTF-8 below version 9 and 128 from it. The hash is definite below
    version 12 and definite or chunked from it in both builds. The `Url`'s text follows each build's
    `decodeString`: definite at every version in the 11.1 layout (f649f975), and definite or chunked from version
    12 in the Leios layout (9df9f98c `Decoding/Decoder.hs:1564-1598`), the layout Dijkstra's state is read in. The
    account's network must be 0 or 1 (`decodeAccountAddressT`, `Address.hs:930-948`). Each refusal is a typed
    error, which `FuturePoolParamsCodecSuite` asserts case by case. Keyed parameters of the Leios layout from
    version 12 are still refused on reading and not written (`DijkstraNotYet.PoolParamsBlsKey`), a gap the Leios
    work closes (#826).
  - The era decoders read a registration's metadata the same way, at their era's version: Conway's at 9, and
    Dijkstra's at 12, where a chunked URL is read as the flat decoder reads it (`DijkstraTxCertSuite`).
  - Every Conway body of the recorded blocks and of the reference's decoding corpora (396, 32 pool registrations
    among those the decoder accepts) reads its registrations' parameters field by field as the flat decoder's,
    in both layouts, and each certificate reads back as the flat one (`EraConwaySuite`).
  - POOL keeps the parameters in the layout of the state they go into: a new pool's in the build's, a
    re-registration's in that of the pool's state. Parameters of the other layout, a certificate decoded for the
    other build, are refused (`LedgerFailure.PoolParamsLayout`; `PoolParamsLayoutSuite`), as the reference, one
    build, never meets them.
- **Dijkstra's transactions** (`ledger/.../era/dijkstra/`: the body, the transaction, the witness set and the
  auxiliary data; `TxBody.hs`, `Tx.hs`, `TxWits.hs` and `TxAuxData.hs` of `eras/dijkstra/impl/src/Cardano/Ledger/Dijkstra/`,
  cardano-ledger 9df9f98c): the families' Dijkstra cases, of both levels.
  - The body mirrors the GADT `DijkstraTxBodyRaw` (`TxBody.hs:172-220`): a sealed `DijkstraTxBody[L]` whose top-level
    case alone has the fee, the collateral, the sub-transactions and the starting balance intervals, so code that
    reads a sub-transaction's fee or collateral does not compile. The fields both levels share are held once.
    Inputs are a sorted set, outputs `Sized` Dijkstra outputs through its checked read, certificates and proposals
    `OSet`s, votes `VotingProcedures[Dijkstra]`, guards `Credential[Guard]` (the reference's `Credential Guard`),
    each required top-level guard's data decoded, and the account balance intervals the reference's
    `AccountBalanceInterval`, with its `Inclusive` and `Exclusive` bounds. The sub-transactions are an `OMap` by id,
    in the order the flat decoder read them, which refused an id twice with its own error; the ordered map refuses a
    repeated id again.
  - The body classes are the reference's up to `DijkstraEraTxBody` (`TxBody.hs:1581-1599`), at `BothLevels`.
    Dijkstra has no `AlonzoEraTxBodyToConway`; its required signers are its guards' key hashes
    (`TxBody.hs:1419`). `redeemerPointerInverse` covers the seven purposes, a guard that is a key naming nothing;
    `allInputs`, a member of `EraTx` at 9df9f98c, stays on `EraTxBody`, as at f649f975, and adds each
    sub-transaction's spent and reference inputs.
  - The witness set and the auxiliary data are Alonzo's, with Dijkstra's scripts, Plutus V4 among them, made from
    the flat decoder's values: each native script read from the bytes it kept, each script with the flat decoder's
    hash. The flat decoder does not keep the bytes of the datums and redeemers (keys 4 and 5), which the script
    integrity hash is over; they are read again from the set's bytes and must be the flat decoder's. The auxiliary
    data's Plutus scripts arrive by language, as the flat decoder lists them and as the reference's
    `Map Language` holds them, where Conway's keep the encoded map's order.
  - The decoders (`DijkstraTx.inBlock` and `fromSubmission`) read bytes with the flat decoder, which decides what is
    refused, and project what it read; the projection takes nothing else, and a body of the other level than it is
    read at is refused (`EraTxError.OtherLevel`). The projections and the constructors of the bodies and
    transactions are reachable from those decoders alone: the projections take a proof (`DijkstraTx.FromBytes`)
    only `DijkstraTx` makes and keeps, so a body's level and bytes are the ones it was read with, given a node
    that is the CBOR parser's. `inBlock` trusts its node to be the parser's, as every decoder of a node does: a
    node built by hand can hold a value its bytes are not
    ([#85, tracked](https://github.com/KD5UJC-XYZ/scarab/issues/85#issuecomment-6999001864)).
    A proposal keeps the flat proposal it was read as, era-indexed (`ProposalRead[E]`), since a
    Dijkstra parameter change has no flat Conway proposal.
  - `DijkstraTxSuite` reads the Dijkstra transactions the repository holds with both decoders: the Leios corpus's
    536 transactions alone (the 273 the flat decoder accepts, 16 of which register a pool, and the 263 it refuses,
    some before either decoder, as bytes that are not one item of CBOR), the 63 transactions of the corpus's blocks
    that decode, the testnet's blocks' 40, the devnet chain's one and its submitted transaction, the 11 and 200
    transactions of the script-context fixtures, the 11 distinct transactions of the UTXOW cases
    (`fixtures/reference/dijkstra-rules`), and, through the mempool decoder, the 149 distinct Dijkstra mempool
    forms of the crossing corpus (`fixtures/reference/dijkstra-crossing`, 9 of which register a pool). Each the flat decoder accepts is read with its fields equal to the flat
    decoder's (outputs and the collateral return field by field with each value's size, metadata in full,
    bootstrap witnesses in full, the size for the fee, which is each crossing case's mempool form's length,
    each certificate and governance action read back, a parameter change as Plutus data against the flat path's),
    a pool registration's parameters field by field with their voting key (4 of the corpus's 16 registrations
    hold one); each it refuses, the era decoder refuses with its error.
    Sub-transactions are checked at their level, in order and by id: 38 recorded on the testnet's chain (of its
    280), 23 synthetic. Every redeemer key of each purpose resolves as the flat resolution does.
  - A pool registration's parameters are the state's (above), with the voting key the Leios build's
    `StakePoolParams` holds; the flat decoder keeps its relays and metadata decoded, so they are read again
    from the body's bytes, as the datums and redeemers are. The transaction class
    instances (`EraTx`, `AlonzoEraTx`), with Dijkstra's annotated transaction and its override of
    `getTotalExUnits` that adds the sub-transactions' units, came with `EraUTxO` (below).
- **Conway's UTxO functions for any era with Conway's classes** (`era/EraUTxO.scala`, `era/conway/ConwayUTxO.scala`,
  `era/EraScript.scala`, `era/EraTx.scala`), the first part of Dijkstra's UTxO instances (#85):
  - `getConwayScriptsNeeded`, `getConwayWitsVKeyNeeded`, `voterWitnesses`, `txNonDistinctRefScriptsSize` and
    `getConwayMinFeeTxUtxo` (`Conway/UTxO.hs:62-194`) are generic in their era over `ConwayEraTxBody`,
    `ConwayEraScript`, `EraTx` and `BabbageEraTxBody`, as the reference's are. These functions, and Alonzo's, Babbage's,
    Mary's and Shelley's of the UTxO set, are the same at f649f975 and 9df9f98c, where Dijkstra's instance calls them.
  - Each purpose is built and read through the script class: Alonzo's builders and readers of the spending,
    minting, certifying and withdrawing purposes (`mkSpendingPurpose` to `toWithdrawingPurpose`,
    `Alonzo/Scripts.hs:205-219` at f649f975, `:207-221` at 9df9f98c) are members of `ConwayEraScript`, where
    `hoistPlutusPurpose` is: the reference has them on `AlonzoEraScript`, whose Babbage instance waits for Babbage's
    purposes ([#85's item](https://github.com/KD5UJC-XYZ/scarab/issues/85#issuecomment-6888255792)). Conway's and
    Dijkstra's instances are the reference's. Two items differ in representation, as the purposes they build hold
    them: a minting purpose's policy is its script hash's `Bytes` where the reference's is a `PolicyID`, and a
    withdrawing purpose's account the node's `RewardAccount` where the reference's is an `AccountAddress`
    ([#85's item on the reference's nominal types](https://github.com/KD5UJC-XYZ/scarab/issues/85#issuecomment-6888273413)).
    Alonzo's spending, withdrawing and minting needs and `getBabbageSpendingDatum` read them, no longer the
    evidence that the era's purpose is Conway's.
  - `getTotalExUnits` is a member of `AlonzoEraTx`, as at 9df9f98c (`Alonzo/Tx.hs:330-331`), with the
    reference's default, the witnesses' redeemers' units added up, which is f649f975's `totExUnits`; `totExUnits`
    is its name kept (`:510-514`), and `alonzoMinFeeTx` and `validateExUnitsTooBigUTxO` read the member, as at
    9df9f98c. Conway's minimum fee and units check are therefore unchanged: `ConwayUtxoChecksSuite`, which compares
    them with the flat rules on every Conway case of the refusal corpora, passes as before. Dijkstra's instance in the
    reference overrides the member to add its sub-transactions' units (`Dijkstra/Tx.hs:417-422`), as Dijkstra's
    transaction class instance does (below).
  - `EraConwaySuite` and `DijkstraPlutusPurposeSuite` build each purpose and read it back by its own reader only;
    `DijkstraTxSuite` reads every purpose that a redeemer key of each corpus body resolves to (both levels) with each
    reader, and only its own reader reads it, its builder giving it back.
- **Dijkstra's transaction and UTxO instances** (`era/dijkstra/DijkstraTx.scala`, `DijkstraStAnnTx.scala`,
  `DijkstraTxInfo.scala`, `DijkstraUTxO.scala`; `Tx.hs`, `UTxO.hs` and `TxInfo.hs` of
  `eras/dijkstra/impl/src/Cardano/Ledger/Dijkstra/`, cardano-ledger 9df9f98c), on Conway's UTxO functions for any era
  (above):
  - `EraTx` and `AlonzoEraTx` of Dijkstra (`Tx.hs:216-253, 413-422`), of both levels: the parts, the annotated
    transaction's transaction and cache, the size, Conway's minimum fee, the phase-two flag, and `getTotalExUnits`, which
    adds each sub-transaction's units to the top level's.
  - `EraUTxO`, `AlonzoEraUTxO` and `DijkstraEraUTxO` of Dijkstra (`UTxO.hs:63-66, 148-163, 200-211, 291-298`): the
    value consumed (Mary's of each body, the sub-transactions' added at the top level) and produced (each body's own
    outputs, donation, proposals' deposits, burned assets and direct deposits, the fee, and the deposits of the batch's
    certificates counted together, the sub-transactions' first); the scripts provided (the sub-transactions' added at
    the top level, the top level's kept on a repeated hash) and needed (Conway's, then each guard that is a script);
    Conway's keys and minimum fee; the supplemental datums of every level at the top; Babbage's spending datum; and the
    annotated transaction's members. `dijkstraConsumed` and `batchNonDistinctRefScriptsSize` with them.
  - The functions are written for Dijkstra, whose levels are both, as Conway's `conwayProducedValue` is for Conway;
    the reference writes them for any era whose levels are both.
  - The annotated transaction (`DijkstraStAnnTx`, `Tx.hs:535-557`) and the two types it names that Dijkstra's script
    contexts give, the context error (`DijkstraContextError`, `TxInfo.hs:155-172`) and the memoised transaction
    information (`DijkstraTxInfoResult`, `TxInfo.hs:302-307`), are the families' Dijkstra cases. Nothing makes an
    annotated transaction yet: its maker (`mkDijkstraStAnnTopTx`, `Dijkstra.hs:108-219`) needs the contexts, which
    come with them ([#85's item](https://github.com/KD5UJC-XYZ/scarab/issues/85#issuecomment-6981185123)). Its
    members are read on built values only, a Gap of that item. The memoised information's slots are the shared
    `PlutusTxInfoResult`, a direct `Either` as 9df9f98c's is (`Alonzo/Plutus/Context.hs:193-196`, above), V4's of
    the V4 `TxInfo` (`:280`, above). The legacy-mode flag is an enum (`PlutusLegacyMode`), the
    reference's `Bool` (TYP-4). `DijkstraEraUTxO`'s member of a sub-transaction's annotated form takes the evidence of
    that level (`SubOnly`, as `TopOnly` gives the top level), as the family named at the sub-transaction level
    outright would be reduced at Conway too, where it has no case.
  - Not here: `DijkstraEraTx`'s `hashTx` ([#85's item](https://github.com/KD5UJC-XYZ/scarab/issues/85#issuecomment-7024127184)),
    and `mkBasicTx`, which the class has for no era here. `validateNativeScript` came with UTXOW whole (above),
    Dijkstra's the reference's `validateDijkstraNativeScript`.
  - `DijkstraUTxOSuite` reads every Dijkstra transaction the repository holds (`DijkstraCorpus`, which
    `DijkstraTxSuite` reads too), at both levels, with the recorded UTxO set where the corpus has one and stand-in
    outputs elsewhere. Against the flat rules: the scripts needed equal `DijkstraPurposes.scriptsNeeded` in order,
    purpose, index, item (a certificate by its key and script) and script hash, guards among them; the execution units
    of the batch and of each sub-transaction, and the batch's reference scripts' size, equal `DijkstraTxMeasures`' and
    the redeemers the flat decoder read; the top level's Plutus scripts provided, by hash and language, equal
    `plutusScriptsProvided`; and the supplemental datums at each level equal the datum hashes the flat decoder read in
    the outputs and the reference inputs' outputs, also where a sub-transaction's reference input names one (crafted)
    and with the sub-transactions taken out of the body, as UTXOW reads it in the legacy mode.
  - The flat path has a minimum fee (`DijkstraUtxo.minFee`), the keys needed (`TxChecks.neededWitnesses`) and the
    supplemental datums (`TxChecks.supplementalDatumHashes`) for a transaction without Dijkstra's own fields, and the
    value consumed and produced only inline: no complete Dijkstra batch comparison. What the reference records stands in
    for it, as component checks under CNF-2, not the I1 verdict or the resulting state. On the 200 transactions recorded
    on the testnet's chain, which its ledger accepted (28 with sub-transactions, 15 with units in one, ten with
    certificates, one with direct deposits, none registering a pool), the value consumed is the value produced, the keys
    needed at each level are a subset of its witnesses (an undercomputed set would pass), the batch's scripts needed
    less its reference scripts are exactly its witnesses, the scripts provided are its witnesses and reference scripts,
    and the top level's witness datums are allowed. On the reference's UTXOW cases, which have no sub-transactions,
    the values of its three `ValueNotConservedUTxO` refusals, the minimum fee of its one `FeeTooSmallUTxO` (a
    transaction without redeemers or reference scripts), the keys of its `MissingVKeyWitnessesUTXOW` and the scripts
    of its `MissingScriptWitnessesUTXOW` and `ExtraneousScriptWitnessesUTXOW` are the functions', and each case it
    accepts conserves value, is witnessed, pays the fee and provides exactly the scripts needed; a crafted witness of a
    script a reference input provides is extraneous, as `babbageMissingScripts` has it.
- **Dijkstra's accounts and certificate state** (`era/dijkstra/DijkstraState.scala`; `State/Account.hs` and
  `State/CertState.hs` of `eras/dijkstra/impl/src/Cardano/Ledger/Dijkstra/`, cardano-ledger 9df9f98c):
  `EraAccounts`, `ConwayEraAccounts`, `EraCertState` and `ConwayEraCertState` of Dijkstra, over Conway's records and
  lenses as the reference's are (`AccountState`'s Dijkstra case is `ConwayAccountState`), with Dijkstra's deposits
  and refunds of a batch: the deposits of the batch's certificates, the sub-transactions' first, and of each level's
  proposals (`dijkstraCertsTotalDepositsTxBody`, `:41-60`); the refunds the batch's certificates declare, no deposit
  looked up (`dijkstraCertsTotalRefundsTxBody`, `:62-81`), at the body's own level (`DijkstraTxBody.toSTxLevel`, the
  reference's `HasEraTxLevel TxBody`, `TxBody.hs:1079-1084`).
  - `DijkstraStateSuite`, on the 217 transactions recorded on the Leios testnet's chain, which its ledger accepted, and
    of the reference's UTXOW cases: the 176 without sub-transactions that the Conway decoder reads have the flat rules'
    deposits and refunds (`Deposits.of`, `DijkstraUtxo.declaredRefunds`); the 13 it refuses, for a field Dijkstra adds
    or changes (a witness set's key 8, a parameter change of keys 45 to 48, the direct deposits' key 25 with the
    balance intervals' key 26, a guard where Conway has a signer), are counted by their typed refusal, and the 28
    batches have no flat computation. Every one
    without certificates has the parameters' deposit for each of its proposals at both levels. Crafted batches give the
    refunds of each level, and the deposits of a pool registered at both levels (counted once), of a pool the state
    holds (not counted) and of proposals at both levels, each total derived by hand from the reference's rules. The
    accounts and the obligations are Conway's instance's, on a state of distinct stake, pool and DRep deposits and an
    account delegated to a pool and a DRep.
- **Dijkstra's UTXOS, UTXO and UTXOW** (`era/rules/DijkstraUtxo.scala`, `era/rules/DijkstraUtxow.scala`; `Rules/Utxos.hs`,
  `Rules/Utxo.hs` and `Rules/Utxow.hs` of `eras/dijkstra/impl/src/Cardano/Ledger/Dijkstra/`, cardano-ledger 9df9f98c):
  - UTXOS is Conway's (`EraRule "UTXOS" DijkstraEra = Conway.UTXOS`): Dijkstra's cases of the failure and event
    families, PPUP's and MIR's with no value, and Conway's injection of Alonzo's UTXOS failures.
  - UTXO and UTXOW are Dijkstra's own. Their failure types are `DijkstraUtxoPredFailure` and
    `DijkstraUtxowPredFailure`, in tag order, with the reference's injections and embeddings. The environment is
    Dijkstra's `UtxoEnv`: the slot, the parameters, the pool state after the sub-transactions, and the certificate
    state and UTxO set before the transaction, which every lookup reads but one: the second bad-inputs check
    reads the threaded state's UTxO set (`utxosUtxo utxos`, `Rules/Utxo.hs:354`), so a top-level input a
    sub-transaction spent is refused.
  - `dijkstraUtxoTransition` runs these in Dijkstra's order and selection, then UTXOS and Babbage's
    `updateUTxOState`:
    - the inputs, against the set before and against the threaded state;
    - Shelley's `validateFeeTooSmallUTxO`, not `feesOK`, and the batch's collateral (`validateBatchCollateral`);
    - the value conserved over the batch, only for a transaction flagged valid; in the legacy mode, also over the
      top level against the pool state after the sub-transactions;
    - no pointer in the collateral return.
  - `dijkstraUtxowTransition` aggregates the scripts needed, the witnesses and the reference scripts over every
    level, and adds the guards' checks: `MissingRequiredGuards` on the signal, then `validateGuardDatums`, written
    for either level for SUBUTXOW. `hasExactSetOfRedeemers` now changes the purposes' forms through
    `ConwayEraScript`'s `hoistPlutusPurpose`, not Conway's evidence, so it serves both eras.
  - **The injections the reference leaves partial** (decided 2026-11-20, #85). `conwayToDijkstraUtxoPredFailure`
    calls `error "Impossible"` on `WrongNetworkWithdrawal` (ENTITIES') and `OutputTooSmallUTxO`
    (`Utxo.hs:572-573`). The uninhabited payload that `MirSigners` uses cannot express this, because Shelley's
    `WrongNetworkWithdrawal` has a value at Dijkstra through ENTITIES. Instead, each shared predicate declares the
    constructor it reports as its result type. `InjectUtxoFailure` and `InjectUtxowFailure` are contravariant in
    the failure type, and Dijkstra's instances are over the union of constructors its UTXO reaches
    (`DijkstraUtxo.ShelleyFailure` and the others). So every instance is total, and the impossible constructors
    have no injection: requesting one does not compile. Conway's instances over its whole types serve the
    narrower requests unchanged.
  - **The body without its sub-transactions** (`txBody & subTransactionsTxBodyL .~ mempty`, `Utxo.hs:380-387`,
    `Utxow.hs:281-285`). The legacy mode's value check and datum check read the top level alone. The node cannot
    make a body again outside the decoder, so `DijkstraUTxO`'s `…WithoutSubTransactions` functions compute those
    values directly; they read no id and no bytes. UTXOW takes the allowed supplemental datums through
    `AlonzoUtxow.missingRequiredDatumsAllowing`.
  - Evidence:
    - `DijkstraRulesOracleSuite`: on each of the reference's 17 UTXOW cases (`fixtures/reference/dijkstra-rules`),
      the era UTXOW runs from the same parameters, certificate state, UTxO set and slot, on the transaction read by
      the era decoder and annotated by `mkDijkstraStAnnTopTx`. Its failures, put in the flat form and written by
      the suite's encoder, are the reference's bytes in its order, and its verdicts are the flat rule's. The flat
      form is never narrowed: an extra redeemer whose `Word32` index the flat failure's `Int` cannot hold is left
      unprojected (`DijkstraUtxowSuite`, at 2³¹−1, 2³¹ and 2³²−1).
    - `DijkstraUtxowSuite`, on the testnet's chain (`fixtures/reference/leios-testnet-chain-contexts`): UTXOW whole
      admits every ranking block's transaction without sub-transactions. It runs from the genesis parameters and
      the chain's cost models, with no certificate state, at the block's slot, with the node's evaluator; every
      Plutus script passes. The scripts each success event reports are, by hash and in order, the contexts the
      reference collected for that transaction: 110 over the 78, of which 17 have several. Of the 94 endorser-block transactions without sub-transactions, 90 are admitted at the
      certifying ranking block's slot. The other four end their validity interval before that slot and are
      admitted only at their endorser block's slot. The slot a closure is applied at is BBODY's question (D8).
    - `DijkstraUtxowSuite`, on built values of every constructor Dijkstra's UTXO reports: each injection equals
      the reference's composition through Conway's failure. The impossible constructors, and Conway's and
      Alonzo's whole failure types, have no Dijkstra injection (`compileErrors`, beside a reachable one that
      has).
    - `DijkstraUtxowSuite`, a component check on the chain's 28 batches: UTXOW admits each against the UTxO set
      before it (the state after SUBLEDGERS is D7c's). On the 22 context refusals
      (`fixtures/reference/leios-testnet-context-refusals`), UTXOS records the batch's collection errors as
      `CollectErrors` (12: 11 at the top level, one in a sub-transaction) and evaluates nothing under
      `NonStatic`.
    - `DijkstraRulesOracleSuite` and `DijkstraRulesSuite`: on the oracle's four admitted cases and the flat
      suite's devnet transaction, with its guarded reference script refused and then holding by a signature,
      the era rule leaves the flat rule's state or gives its failures; under `NonStatic` it gives the flat
      rule's verdict on all 17 oracle cases.
    - `DijkstraUTxOSuite`: on every batch whose body the suite can write again, the values and datums without the
      sub-transactions equal the batch's functions applied to the body rewritten without key 23.
    - Conway's era suites (`EraUtxosSuite`, `EraUtxoSuite`, `EraUtxowSuite`, `ConwayUtxoChecksSuite`,
      `ConwayUtxowChecksSuite` and the reference suites) keep their expected values.
  - Incomplete (CNF-2):
    - the 28 batches of the chain as the reference judges them: UTXOW reads the state after SUBLEDGERS (D7c);
      here they are a component check;
    - the forecast: the history given ends far after the chain, so `OutsideForecast` is not exercised at Dijkstra;
    - refusals that no recording holds
      ([#85's item](https://github.com/KD5UJC-XYZ/scarab/issues/85#issuecomment-7056846371)): a guard's
      (`MissingRequiredGuards`, `MalformedGuardDatums`), the legacy mode's value, a pointer in the collateral return,
      an input a sub-transaction spent, a batch's collateral, and a Plutus refusal;
    - the refusals are compared through the flat form and the suite's encoder: the era failures have no encoder
      yet (#796).
  - Not yet (#85): the sub-transactions' rules and LEDGER (D7c, D7e); ENTITIES and the certificate rules, and
    GOV, are the next bullets'.
- **Dijkstra's certificate rules and ENTITIES** (`era/rules/DijkstraCerts.scala`,
  `era/rules/DijkstraCertsFailures.scala`; `Rules/{Entities,Cert,Certs,Deleg,Pool,GovCert}.hs` of
  `eras/dijkstra/impl/src/Cardano/Ledger/Dijkstra/`, cardano-ledger 9df9f98c):
  - CERTS, DELEG and GOVCERT are Conway's transitions at Dijkstra, with Dijkstra's cases of the failure and event
    families. CERTS's, CERT's and DELEG's failures are Conway's types; GOVCERT's is `DijkstraGovCertPredFailure`,
    Conway's renamed, with `conwayToDijkstraGovCertPredFailure`. The reference's injections and embeddings come
    with them (`InjectCertFailure.dijkstra` and the others), as Conway's do. Shelley's `AccountAlreadyRegistered`
    goes into Dijkstra's DELEG as `StakeKeyRegisteredDELEG`, tag 2 (`Deleg.hs:19-20`,
    `InjectDelegFailure.dijkstra`). LEDGER composes it through CERT, CERTS and ENTITIES (`Ledger.hs:113-119`),
    which have no instance of their own. Conway's era types follow the 11.1 build's ledger, which has no such
    instance.
  - CERTS runs the era's CERT through `CertRule`, the type family `EraRule "CERT"` as a class of the era's
    transition: Conway's CERT at Conway (LEDGER passes `CertRule.conway`), Dijkstra's at Dijkstra. Its second
    parameter is what the era's CERT raises short of a verdict: nothing at Conway, POOL's `PoolDefect` at Dijkstra.
  - CERTS's base case is the era's (`CertsBaseCase`). Conway's is the 11.1 build's below version 11. Dijkstra's
    leaves the state as it is (`Empty -> pure certState`, `Conway/Rules/Certs.hs:220`), by its type and not by
    its protocol version. At 9df9f98c CERTS's failure has `CertFailure` alone (`Conway/Rules/Certs.hs:111-114`).
    So the 11.1 build's `WithdrawalsNotInRewardsCERTS` has no value at Dijkstra (`CertsWithdrawals`, as
    `MirSigners` has none at Conway), and `DijkstraCertsStored.toStored` is total. The environment's
    transaction, which only the 11.1 build's case reads, stays Conway's field.
  - CERT and POOL are Dijkstra's own. CERT makes a delegation certificate Conway's (`dijkstraToConwayDelegCert`)
    for Conway's DELEG and runs Dijkstra's POOL on the pool state. POOL (`DijkstraPoolPredFailure`, tags 0, 1 and
    3 to 7) has no version gate. It keeps the count of each VRF key hash's holders as the reference's invariant on
    `psVRFKeyHashes` says (`Pool.hs:176-186`), and a new pool holds its voting key from the current epoch
    (`mkStakePoolState cEpoch SNothing`). It checks the key's proof of possession only under a policy that runs the
    static checks (`checkFailOnJustStatic`); the policy is a value of the judgment (`CertRule.dijkstra`), as the
    reference's is of its options. A proof that does not verify is `BlsKeyInvalidProofOfPossession`. A failure of
    the library, or a key or proof the decoder would not admit, is POOL's own defect
    (`PoolDefect.BlsKeyUnverifiable`, with the pool's `KeyHash[StakePool]` and the `BlsError`), as the Leios
    committee's selection treats them. POOL raises it through a capability of its own, as GOV raises `GovDefect`
    and UTXO `UtxoStateDefect`, and CERT and ENTITIES raise it as it is; the node's flat `LedgerFailure` has no such
    case. A decoded certificate cannot reach it through its point encoding, as the decoder refuses such a point
    before POOL; an exception of the native verifier can. POOL is generic in its era, where the reference's is
    generic in its rule too, for SUBPOOL (D7c).
  - ENTITIES (`EntitiesPredFailure`, tags 0 to 13, and `EntitiesEvent`) is written at Dijkstra, the one era with
    the rule, as Dijkstra's UTXO is. `dijkstraEntitiesTransition` runs, in the reference's order:
    - the withdrawals' network, the direct deposits' network, the balance intervals against the current accounts
      and the starting ones against the original accounts;
    - the withdrawals against the original accounts: in the normal mode the top level's missing accounts and the
      batch's sums past a balance, in the legacy mode the sub-transactions' sums;
    - only when nothing has failed (`whenFailureFree`), the top level's withdrawals against the current accounts;
    - the DReps' expiries, the top level's withdrawals applied, then CERTS as a sub-rule;
    - the direct deposits against the accounts after CERTS, then added to them.
  - A withdrawal or deposit applied changes a balance in `Word64` arithmetic that wraps, as `subtractCompactCoin`
    and `addCompactCoin` do (`CompactCoin.subtract` and `add`), a Mirror recorded in the
    [Dijkstra compatibility page](../compatibility/dijkstra.md) under ADR 0001's arithmetic procedure. A
    withdrawal wraps a balance only on a transaction ENTITIES already refuses, as the reference's own comment says
    (`Entities.hs:291-296`), and a later failure of that refusal reports it. ENTITIES alone does not stop a deposit
    from wrapping one; the ledger's value conservation does, as no transaction deposits more than it consumes. Each map of the body is read into the
    reference's `AccountAddress` keys, which every decoded one is; a value outside the vocabulary is raised
    (`LedgerFailure.Vocab`).
  - **The injections the reference leaves partial**, by D7a's decision (above). Conway's LEDGER failure is
    impossible for ENTITIES on every constructor (`conwayToDijkstraEntitiesPredFailure`, `Entities.hs:413-426`),
    and no predicate ENTITIES runs reports one, so it has no injection. Shelley's UTXO failure is impossible on
    every constructor but `WrongNetworkWithdrawal` (`Entities.hs:428-443`). `validateWrongNetworkWithdrawal` now
    declares that constructor as its result, and ENTITIES injects it alone. That injection reads the accounts into
    `AccountAddress`, a conversion total on decoded bodies but not by type, as the era bodies hold withdrawals
    keyed by the node's reward account
    ([#85's item](https://github.com/KD5UJC-XYZ/scarab/issues/85#issuecomment-7058657335)). So it is a function of
    the rule whose `Left` the rule raises, not an instance. `InjectEntitiesFailure` is invariant: with that
    constructor a function, no instance is over a narrowed type.
  - Evidence:
    - `DijkstraRulesOracleSuite`: on the reference's 20 `certs` cases (`fixtures/reference/dijkstra-rules`), CERTS
      on the era types, from the same parameters and state, gives the reference's refusal byte for byte, through
      `DijkstraCertsStored.toStored` and the suite's encoder, the failures of `mixed-failures` across the
      recursion's depths among them. It gives the flat rule's verdicts on all 20 and the flat rule's certificate
      state on each case both admit. It leaves the flat rule's state after the setup of each of the 16 `gov` and 2
      `utxow` cases that have one. ENTITIES admits the transaction of the devnet block the reference accepted
      (`bbody/devnet-37-genesis-utxo`) from that block's state and leaves the state as it was; the flat rules halt
      there at ENTITIES. That transaction has no withdrawal, certificate, deposit or interval, so the case shows
      only that ENTITIES admits a plain transaction.
    - `DijkstraCertsSuite`, POOL:
      - the voting key and proof of a pool of the Leios devnet (`fixtures/reference/leios-genesis`) register, the
        key held from the current epoch;
      - that key with the proof of another key is refused with `BlsKeyInvalidProofOfPossession`, and admitted
        under `NonStatic`; a key the decoder would not admit is POOL's defect `PoolDefect.BlsKeyUnverifiable`, not
        read under `NonStatic`;
      - the 16 pool registrations of the Leios build's decoding corpus (`fixtures/reference/leios-testnet-blocks`),
        each registration certificate at the top level of a transaction the decoder accepted, found by its decoded
        constructor. The one whose metadata hash is 70 bytes, which the decoder admits, is refused with
        `PoolMedataHashTooBig` of 70 and nothing else. The other 15 are admitted, the 4 with a voting key holding it
        from the current epoch. A component check, as the reference's POOL did not judge them;
      - the VRF counts equal the reference's own recomputation (`populateVRFKeyHashes`) after each of a sequence
        of registrations and re-registrations, and a key hash another pool holds is refused;
      - CERTS tells `RegisterPool` then `ReregisterPool`, embedded in CERT's and CERTS's events.
    - `DijkstraCertsSuite`, the testnet chain (`fixtures/reference/leios-testnet-chain-contexts`):
      - CERTS on the certificates of the 8 transactions that hold them at the top level gives the flat rule's
        failures or state, from the same empty state;
      - ENTITIES runs on all 200 transactions from the empty state with no defect. On each, its complete ordered
        failures, or the certificate state it leaves, equal those derived without its transition: ENTITIES'
        predicates on the body's maps, CERTS alone one level down, and the direct deposits added by hand, in the
        order the reference reports them. It refuses 23. This is a component check: the reference judged them on
        the chain's state.
      - On the chain's first transaction that registers an account at the top level, encoded again with a direct
        deposit to that account (body field 25), ENTITIES admits it and the account holds the deposit. So the
        deposits are checked and added after CERTS.
      - On the transaction that withdraws from an account and unregisters it with another (crafted accounts):
        drained, it is admitted with both unregistered, so the withdrawal is applied before CERTS; withdrawing past
        the balance gives `WithdrawalAmountsExceedingOriginalBalance`, no check of the current accounts, and DELEG's
        `StakeKeyHasNonZeroAccountBalanceDELEG` of the wrapped balance; an account the current accounts lack gives
        `WithdrawalAccountsMissing`, and is not reported when the original balance is short (`whenFailureFree`).
    - `DijkstraCertsSuite`, the rest:
      - on built values, each injection into CERT, CERTS and ENTITIES equals the reference's composition;
      - DELEG's injection of Shelley's `AccountAlreadyRegistered` is `StakeKeyRegisteredDELEG`, which equals DELEG's
        own refusal of a second registration, through CERT and CERTS;
      - CERTS's 11.1 withdrawals' case does not compile at Dijkstra (`compileErrors`, beside Conway's, which does);
      - the constructors the reference calls impossible for ENTITIES have no injection (`compileErrors`, beside a
        reachable one that has);
      - ENTITIES' predicates on crafted accounts: the balance intervals, the direct deposits and the withdrawals in
        each mode.
    - Conway's era suites (`ConwayCertsChecksSuite`, `CertsEpochWrapSuite`, `PoolParamsLayoutSuite`,
      `ConwayLedgerChecksSuite`, `LedgerInjectionSuite`, the upstream certificate and LEDGER conformance suites)
      keep their expected values.
  - Incomplete (CNF-2):
    - the chain's transactions as the reference judged them: that needs the chain's certificate state, which the
      fixture does not record; here they are a component check from the empty state;
    - the certificates of the 2 chain transactions that hold them in a sub-transaction: SUBCERTS's (D7c); and
      ENTITIES on the chain's 28 batches as the reference judged them, which reads the state after SUBLEDGERS;
    - the `gov` cases' own verdicts, which are GOV's (the next bullet); only their setups are compared here;
    - a pool registration against the flat rules, which halt on one (`DijkstraPending.PoolRegistration`): POOL's
      registrations are compared with the reference's invariant and recorded parameters, not with a flat rule;
    - refusals that no recording holds: any of ENTITIES' own failures, and a pool registration with a voting key,
      which the oracle's cases do not reach (its scope excludes ENTITIES and pool registrations);
    - the refusals are compared through the flat Dijkstra form and the suite's encoder: the era failures have no
      encoder yet (#796).
  - Not yet (#85): the committee and its proposals in `EntitiesEnv` are the node's records, as in `CertsEnv` (the
    Gap of [#85's item](https://github.com/KD5UJC-XYZ/scarab/issues/85#issuecomment-6923349468)); the era bodies'
    withdrawals and deposits keyed by `AccountAddress`
    ([item](https://github.com/KD5UJC-XYZ/scarab/issues/85#issuecomment-7058657335)); the sub-transactions' rules,
    which reuse POOL and ENTITIES' checks (D7c).
- **Dijkstra's GOV** (`era/rules/DijkstraGovFailures.scala`, `era/rules/GovFailures.scala`; `Rules/Gov.hs` of
  `eras/dijkstra/impl/src/Cardano/Ledger/Dijkstra/`, cardano-ledger 9df9f98c):
  - GOV is Conway's transition at Dijkstra (`transitionRules = [Conway.conwayGovTransition]`, `Gov.hs:220`), on
    Conway's `Proposals` and Conway's environment and signal (`Gov.hs:211-216`), unchanged: Conway's `Gov.hs` and
    `Governance/Proposals.hs` at 9df9f98c are f649f975's but for the failure type's derived `Ord`, and the voting
    functions of `Governance/Internal.hs` and `pvCanFollow` are the same. What Dijkstra changes reaches it
    through the parameter class alone: `ConwayEraPParams[Dijkstra]`'s `ppuWellFormed` (`Dijkstra/PParams.hs:1149-1175`,
    with no version gate) and the groups of Dijkstra's fields, its keys 34 to 56 among them, which decide whether
    a stake pool may vote on a parameter change. Dijkstra adds no check of its own to GOV and removes none.
  - The failure type is `DijkstraGovPredFailure` (`Gov.hs:73-114`), a type of its own with Conway's 19
    constructors under their names, in tag order (`:135-155`), with `ConwayGovPredFailure`'s fields. `conwayToDijkstraGovPredFailure` (`:222-243`) is total, so
    D7a's decision on partial injections has nothing to narrow. `EraRuleFailure "GOV"` and `EraRuleEvent "GOV"`
    have Dijkstra's cases, the event Conway's (`:121-123`), and `InjectGov.dijkstra` is the reference's two
    instances (`:127-130`). `DijkstraGovPredFailure.toStored` gives a failure's flat form, Conway's, one-way glue the
    comparisons read; a parameter change, which the flat Conway action cannot hold, has none
    (`GovActionNotRead`).
  - The transition is generic in its era, where the reference's is generic in its rule too, for SUBGOV (D7c). The
    LEDGER constructor and injections (`DijkstraGovFailure`, `Ledger.hs:101, 189-196`) come with Dijkstra's LEDGER
    (D7e).
  - Evidence:
    - `DijkstraRulesOracleSuite`: on the reference's 16 `gov` cases (`fixtures/reference/dijkstra-rules`), GOV at
      the root, as the oracle applies it, from the certificate state Dijkstra's era CERTS leaves after the case's
      setup and the default store of proposals, the signal read by the era's decoders. Its 10 refusals, put in
      the flat form by `toStored` and written by the suite's encoder, are the reference's bytes in its order,
      `committee-conflicting`'s two reversed at the root among them. Its verdicts are the flat rule's on all 16.
      On the 6 both admit, its proposals after, written as the flat rule keeps them, are the flat rule's, and its
      events are the sequence Conway's transition tells (`GovNewProposals`, then `GovRemovedVotes`), checked
      against that sequence: the oracle records no events, and the flat rule tells none. The flat form restores an
      action from the proposal it was read as, so `gov/treasury-zero`'s failure is also checked on the era type:
      its withdrawal of zero to the recorded account, with no guardrails script.
    - `DijkstraGovSuite`, the testnet's chain (`fixtures/reference/leios-testnet-chain-contexts`): its only two
      proposals, parameter changes of the endorser-block limits (keys 45 and 46, then 47 and 48), which the chain's
      ledger admitted. From a state that registers their return account, under the testnet's genesis parameters
      and constitution, at their slots' epochs, they are admitted in sequence, the second the child of the first,
      and the second is admitted on a store whose root is the first, as when the first was enacted. The second is
      refused with `InvalidPrevGovActionId` on the default store, the first with `InvalidGuardrailsScriptHash`
      under no constitution, and with `ProposalDepositIncorrect` and `ProposalReturnAccountDoesNotExist` under
      another deposit and no account. A registered stake pool and DRep vote on the first, since those keys are of
      the security group (`Dijkstra/PParams.hs:258-269`).
    - `DijkstraGovSuite`, the rest: the Leios build's decoding corpus's empty parameter change is
      `MalformedProposal`, and its return account of the other network refused after it; each of Conway's
      failures, built, is Dijkstra's of the same tag and fields, through `InjectGov.dijkstra` too, and the events
      pass as they are.
    - Conway's GOV suites (`ConwayGovChecksSuite`, `UpstreamGovernanceFunctionalSuite`, `UpstreamGovConformanceSuite`,
      `EraProposalsSuite`) and the LEDGER suites that run GOV keep their expected values.
  - Incomplete (CNF-2):
    - the chain's proposals as the reference judged them: the fixture records neither the chain's certificate state
      nor its proposals, so each verdict there is the rule's, derived by hand. No other transaction of the
      recorded chain and block samples proposes or votes at the top level (the chain's other 198, the testnet's
      blocks' 40, the corpus's blocks' 63 and the devnet chain's one). The votes and proposals of the Leios
      build's decoding corpus, and of the hand-built `everything` and `governance` cases of the script-context
      fixture (`fixtures/reference/leios-testnet-contexts`), carry no GOV verdict;
    - refusals that no recording holds, at Dijkstra: a parameter change, malformed or not, against the reference,
      which the oracle's cases exclude, and the failures over votes beyond `VotersDoNotExist` and
      `GovActionsDoNotExist`;
    - a sub-transaction's proposals and votes: SUBGOV's (D7c);
    - the refusals are compared through the flat form and the suite's encoder: the era failures have no encoder
      yet (#796).
  - Not yet (#85): what Conway's GOV leaves, the Dijkstra copy inherits: the reference's typed voter in the votes
    and the failures ([item](https://github.com/KD5UJC-XYZ/scarab/issues/85#issuecomment-6923023870)), the
    governance state's classes and the committee as the reference's
    ([item](https://github.com/KD5UJC-XYZ/scarab/issues/85#issuecomment-6923488137)), and the flat GOV's order of
    its vote failures ([item](https://github.com/KD5UJC-XYZ/scarab/issues/85#issuecomment-7054122427)).
- **Dijkstra's SUBUTXO and SUBUTXOW** (`era/rules/DijkstraSubUtxo.scala`, `era/rules/DijkstraSubUtxow.scala`;
  `Rules/SubUtxo.hs` and `Rules/SubUtxow.hs` of `eras/dijkstra/impl/src/Cardano/Ledger/Dijkstra/`, cardano-ledger
  9df9f98c):
  - The failure types are `DijkstraSubUtxoPredFailure` (tags 0 to 4 and 6 to 10) and `DijkstraSubUtxowPredFailure`
    (0 to 17), in tag order, with their events (`DijkstraSubUtxoEvent`, `DijkstraSubUtxowEvent`), the reference's
    injections and the embedding of SUBUTXO. `EraRuleFailure` and `EraRuleEvent` of each are match types with
    Dijkstra's case alone. The environment is `SubUtxoEnv`: the slot, the parameters, the certificate state SUBLEDGER
    was given, from before its SUBENTITIES and SUBGOV (`SubLedger.hs:248, 307`: the state SUBLEDGERS threads from the
    earlier sub-transactions), the UTxO set before the whole transaction and the top level's validity flag. Each runs on one
    sub-transaction's annotated form (`mkDijkstraStAnnSubTx`, through `subTransactionsStAnnTx`).
  - `dijkstraSubUtxoTransition` runs, in the reference's order: the validity interval, the forecast, the outputs'
    size, an empty input set, the inputs against the threaded state's UTxO set alone, the bootstrap attributes (on the
    signal), the outputs' least lovelace, and the outputs' and the body's network (on the signal). When the top level
    is flagged valid, by the environment's flag, it adds the sub-transaction's donation and moves its inputs and
    outputs with the instant stake (`updateUTxOAndInstantStake`, its `TxUTxODiff` told), with no deposit and no fee;
    flagged invalid, it leaves the state as it is.
  - `dijkstraSubUtxowTransition` runs, of the sub-transaction alone and every lookup against the UTxO set before the
    transaction: the signatures (on the signal), the failing needed native scripts, the keys needed against the
    environment's certificate state, the datums, the auxiliary data (on the signal), the integrity hash, the
    redeemers, the well-formed scripts of its witnesses and outputs (on the signal) and the required guards' datums
    (D7a's `validateGuardDatums` at the sub-transaction level); then SUBUTXO. The missing and extraneous scripts and
    the required guards are UTXOW's, over every level, and no script is evaluated: UTXOS evaluates the batch's.
  - **The injections the reference leaves partial**, by D7a's decision. `dijkstraUtxoToDijkstraSubUtxoPredFailure`
    (`SubUtxo.hs:304-329`) calls 13 constructors impossible, and `dijkstraUtxowToDijkstraSubUtxowPredFailure`
    (`SubUtxow.hs:337-364`) 3. The UTXOW predicates SUBUTXOW runs now declare the constructors they report, as
    UTXO's did in D7a, and each instance is over the constructors the rule reaches (`DijkstraSubUtxo.UtxoFailure`,
    `DijkstraSubUtxow.UtxowFailure` and the others). UTXOW's `UtxoFailure` is left out too (decided 2026-11-21, #85):
    the reference maps it through SUBUTXO's partial injection of its payload, and no predicate SUBUTXOW runs reports
    it, as SUBUTXO's failures reach SUBUTXOW through the embedding. `MaxTxSizeUTxO`, which the reference maps and
    SUBUTXO does not report, is kept in the domains of Dijkstra's UTXO failure and Conway's; the earlier eras' domains
    are the constructors SUBUTXO reports, as D7a's are, so Shelley's `MaxTxSizeUTxO` has no injection into SUBUTXO.
    Conway's and Dijkstra's UTXOW keep their instances through the contravariant classes. The failures have no flat form: the flat types have none for a sub-transaction's.
  - Evidence (`DijkstraSubUtxowSuite`):
    - the testnet's chain (`fixtures/reference/leios-testnet-chain-contexts`): on each of the 28 batches, 38
      sub-transactions, which the chain's ledger accepted, SUBUTXOW admits each sub-transaction, threaded in order from
      the batch's recorded outputs, from the genesis parameters and the chain's cost models, with no certificate
      state, its signatures and integrity hashes checked; each tells the outputs it spends and creates, moves their
      instant stake (6 of the 38 change it) and leaves the fees, deposits and donation as they were. D7a's UTXOW then
      admits the top level on the threaded state, at the slot the fixture records (an endorser block's batch at the
      certifying ranking block's), and the batch leaves every level's inputs spent and outputs created. No sub-transaction registers a pool, so the pool state after the
      sub-transactions is the one before. A component check: SUBENTITIES and SUBGOV are not run, and the certificate
      state is not the chain's;
    - a sub-transaction judged again on the state it left is refused with `SubBadInputsUTxO` of its inputs alone,
      which the set before the transaction still holds, and so with the top level flagged invalid, as the predicates run
      before the flag is read; flagged invalid, an admitted one leaves the state as it is and tells nothing; a
      sub-transaction encoded again with a donation (body field 23's first, field 22) adds it to a pot that held one,
      the fees and deposits as they were, and adds nothing flagged invalid; under other cost models, on the other network, a sub-transaction whose inputs are spent is
      refused with SUBUTXO's block first and then the integrity hash, its supplied and computed hashes and the bytes
      the computed one is over as the rule's functions give them, as the reference's list is at the root, and
      without the network's failure under `NonStatic`;
    - on built values of every constructor the rules report, each injection equals the reference's composition
      through Conway's and Dijkstra's failures, the two named functions written out again in the suite case for
      case; the impossible constructors, UTXOW's `UtxoFailure`, and whole failure types have no injection
      (`compileErrors`, beside reachable ones that have);
    - Conway's era suites, D7a's, D7b's and D7d's Dijkstra suites and the node's Conway and upstream suites keep their
      expected values.
  - Incomplete (CNF-2):
    - the batches as the reference judged them: SUBLEDGER runs SUBENTITIES and SUBGOV before SUBUTXOW (the next parts
      of D7c), which reads the certificate state from before them, threaded from the earlier sub-transactions, and the
      fixture does not record the chain's certificate state;
    - refusals that no recording holds: any of SUBUTXO's or SUBUTXOW's, so its refusals here are the rules' on crafted
      states and parameters, and its constructors are built values; a sub-transaction's validity interval, network and
      auxiliary data, which no chain sub-transaction has, and the forecast, as in D7a;
    - the context refusals (`fixtures/reference/leios-testnet-context-refusals`, 6 cases with 9 sub-transactions)
      record each level's collection, not a verdict of the rules; the one refused in a sub-transaction is UTXOS's
      refusal at the top level (D7a), and SUBUTXOW collects nothing; no case of `fixtures/reference/dijkstra-rules`
      has a sub-transaction; the flat rules halt on one;
    - the failures are not compared with the reference's bytes: the era failures have no encoder yet (#796).
  - Not yet (#85): LEDGER (D7e); SUBENTITIES and the sub-transactions' certificate rules, then SUBGOV, SUBLEDGER and
    SUBLEDGERS, are the next bullets'.
- **Dijkstra's sub-transaction certificate rules and SUBENTITIES** (`era/rules/DijkstraSubCerts.scala`,
  `era/rules/DijkstraSubEntities.scala`; `Rules/{SubDeleg,SubPool,SubGovCert,SubCert,SubCerts,SubEntities}.hs` of
  `eras/dijkstra/impl/src/Cardano/Ledger/Dijkstra/`, cardano-ledger 9df9f98c):
  - SUBDELEG, SUBPOOL and SUBGOVCERT have failures that are newtypes over DELEG's, POOL's and GOVCERT's
    (`DijkstraSubDelegPredFailure` and the others; SUBPOOL's event too), and run the top-level rules' transitions
    (`transitionRules = [Conway.conwayDelegTransition]` and the others). Each of their injections is the newtype after
    the top-level rule's injection (`SubDeleg.hs:57-63`, `SubPool.hs:56-67`, `SubGovCert.hs:61-67`; SUBDELEG's of
    Shelley's `AccountAlreadyRegistered` is `DelegAccountAlreadyRegistered`, DELEG's own at Dijkstra, `Deleg.hs:19-20`),
    so each transition here is D7b's with its judge and events contramapped by the newtype (decided 2026-11-21, #85),
    not a transition made generic in its rule.
  - SUBCERT (tags 1 to 3) and SUBCERTS are Dijkstra's own. SUBCERT sends a certificate to the sub-rule of its kind, as
    Dijkstra's CERT does; SUBCERTS recurses on the prefix as CERTS does, written as the same fold, and its empty
    sequence leaves the state as it is. `conwayToDijkstraSubCertPredFailure` and `conwayToDijkstraSubCertsPredFailure`
    are total (the 11.1 build's withdrawals' case of CERTS has no value at Dijkstra). `SubCertsEnv` holds the
    sub-transaction, which no rule reads, and the committee and its proposals as the node's records, as `CertsEnv`.
  - SUBENTITIES (`SubEntitiesPredFailure`, tags 0 to 8) runs, in the reference's order: the withdrawals' network, the
    direct deposits' network, the balance intervals against the current accounts, the withdrawals of an account the
    original and then the current accounts lack (`withdrawalsMissingAccounts`); the DReps' expiries for the
    sub-transaction and its withdrawals applied, whatever failed; SUBCERTS; the direct deposits against the accounts
    after it, then added. It checks no withdrawal's amount and has no `whenFailureFree`, unlike ENTITIES.
    `updateDormantDRepExpiries` and `updateVotingDRepExpiries` are now of a transaction of either level, as the
    reference's are.
  - **The injections the reference leaves partial**, by D7a's decision. `entitiesToSubEntitiesPredFailure`
    (`SubEntities.hs:317-338`) calls 8 of ENTITIES' constructors impossible, and `conwayToDijkstraSubEntitiesPredFailure`
    (`:302-315`) every one of Conway's LEDGER failure. ENTITIES' predicates SUBENTITIES runs now declare the
    constructors they report, `InjectSubEntitiesFailure` is contravariant, and its instance is over the 6 constructors
    the reference maps; Conway's LEDGER failure has no instance. Shelley's `WrongNetworkWithdrawal` goes through D7b's
    function, which now declares its constructor, so SUBENTITIES' is a function too.
  - Evidence:
    - `DijkstraRulesOracleSuite`: on the reference's 20 `certs` cases (`fixtures/reference/dijkstra-rules`), from the
      same state, SUBCERTS's failures are CERTS's, which are the reference's bytes, through
      `conwayToDijkstraSubCertsPredFailure`, in the same order, and its state is CERTS's on each case both admit. A
      component check: the reference applied CERTS, not SUBCERTS, to them.
    - `DijkstraCertsSuite`, SUBCERTS against CERTS in the same way on a pool and an account registered twice, the
      certificates of the chain's 8 transactions that hold them at the top level and of its 2 sub-transactions that
      hold them, which SUBCERTS admits from the empty state; the second registration of an account is DELEG's refusal
      in SUBDELEG's newtype, as SUBDELEG's injection of `AccountAlreadyRegistered` gives it.
    - `DijkstraCertsSuite`, SUBENTITIES on the chain's 38 sub-transactions from the empty state: the 3 that withdraw are
      refused for the accounts the current and the original accounts lack, in the reference's order at the root; the 2
      that register an account leave SUBCERTS's state; the other 33 leave the state as it was. On crafted accounts a
      withdrawal that drains an account registered in both is admitted, and one of an account the original accounts
      lack gives `SubWithdrawalAccountsMissingFromOriginal` alone. A component check: the reference judged them on the
      chain's state.
    - `DijkstraCertsSuite`, SUBENTITIES' steps on crafted sub-transactions (a chain sub-transaction given the fields
      of a case, encoded again): the withdrawal and unregistrations of a chain transaction, from an account holding
      more than the withdrawal and missing from the original accounts, give that failure and SUBDELEG's of the balance
      the withdrawal leaves, so the withdrawal is applied whatever failed and before SUBCERTS; a direct deposit to the
      account a chain sub-transaction registers is admitted and added, so it is checked after SUBCERTS; a DRep's vote
      sets its expiry, the others' left as they were; a proposal with three dormant epochs moves every DRep's expiry
      up by them but one it would leave before the current epoch, and resets the count; the script-context fixture's
      sub-transaction (`fixtures/reference/leios-testnet-contexts`, "sub-transaction scripts"), with withdrawals,
      deposits and intervals of both networks, gives the seven failures of the reference's order at the root.
    - `DijkstraCertsSuite`, SUBCERTS's fold on a small stack (STK-3, `PreprodDeepTx.onSmallStack`), with its
      judgment's interpretation and the flattening of its failures: an account registered 5000 times, refused 4999
      times, and registered and unregistered 2500 times, admitted.
    - `DijkstraCertsSuite`, on built values: each injection is the newtype after the top-level rule's; CERT's, CERTS's
      and ENTITIES' failures map as the reference's functions do, written out in the suite; the impossible
      constructors, ENTITIES' whole type, Conway's LEDGER failure and Shelley's have no SUBENTITIES instance
      (`compileErrors`, beside a reachable one that has).
    - D7a's, D7b's and D7d's Dijkstra suites, Conway's era suites and the node's Conway and upstream suites keep their
      expected values.
  - Incomplete (CNF-2):
    - the chain's sub-transactions as the reference judged them: that needs the chain's certificate state, which the
      fixture does not record, and SUBLEDGER (D7c's next part);
    - refusals that no recording holds: any of SUBCERTS's, SUBENTITIES' or the newtyped rules' by the reference itself;
      a sub-transaction's direct deposit, balance interval, vote, proposal or pool certificate, which none of the
      chain's 38 has, so those cases are crafted, their verdicts the rules';
    - the 9 sub-transactions of the context refusals (`fixtures/reference/leios-testnet-context-refusals`), some of
      which vote or name the current treasury: they record each level's collection, not a verdict of the rules, so
      SUBENTITIES is not run on them;
    - the failures are not compared with the reference's bytes: the era failures have no encoder yet (#796).
  - Not yet (#85): LEDGER (D7e); SUBGOV, SUBLEDGER and SUBLEDGERS are the next bullet's.
- **Dijkstra's SUBGOV, SUBLEDGER and SUBLEDGERS** (`era/rules/DijkstraSubLedger.scala`;
  `Rules/{SubGov,SubLedger,SubLedgers}.hs` of `eras/dijkstra/impl/src/Cardano/Ledger/Dijkstra/`, cardano-ledger
  9df9f98c):
  - SUBGOV's failure and event are newtypes over Dijkstra's GOV failure and Conway's GOV event
    (`DijkstraSubGovPredFailure`, `DijkstraSubGovEvent`, `SubGov.hs:43-64`), and each of its injections is the newtype
    after GOV's (`SubGov.hs:50-56`), so its transition is D7d's GOV with its judge and events contramapped by the
    newtypes (D4), as SUBDELEG's, SUBPOOL's and SUBGOVCERT's are.
  - SUBLEDGER (`DijkstraSubLedgerPredFailure`, tags 1, 2, 3 and 5; `DijkstraSubLedgerEvent`; `SubLedgerEnv`) runs, in
    the reference's order (`SubLedger.hs:245-311`): the treasury the sub-transaction declares against the chain's,
    whatever the top level's flag (Conway's `validateTreasuryValue`, now of either level and declaring its
    constructor); when the top level is flagged valid, SUBENTITIES with the committee and its proposals of the
    governance state and the accounts before the batch, then SUBGOV with the sub-transaction's id and the certificate
    state after SUBENTITIES; then SUBUTXOW with the certificate state SUBLEDGER was given. Flagged invalid, SUBENTITIES
    and SUBGOV do not run. The certificate state after is SUBENTITIES', or the one given. SUBLEDGERS
    (`DijkstraSubLedgersPredFailure`, `DijkstraSubLedgersEvent`) folds SUBLEDGER over the sub-transactions (`foldM`,
    each a sub-rule on the state the one before left), which `tailRecM` runs in constant stack. Each rule's embeddings
    are `EmbedInSubLedger` and `EmbedSubLedgerInSubLedgers`.
  - The proposals are the era's, in their own capability beside the ledger state, which SUBGOV judges and leaves, and
    from which the committee's proposals SUBENTITIES reads are taken (`proposalsWithPurpose`, now on the era's
    `Proposals`), so both read the proposals the earlier sub-transactions left: the node's record of them in the
    governance state cannot hold a Dijkstra parameter change (the Gap of
    [#85's item](https://github.com/KD5UJC-XYZ/scarab/issues/85#issuecomment-6923488137)). The committee and the
    constitution are the governance state's, and the committee's proposals are put in the node's record that
    `SubEntitiesEnv` holds (the Gap of [#85's item](https://github.com/KD5UJC-XYZ/scarab/issues/85#issuecomment-6923349468)).
  - **The injection the reference leaves partial**, by D7a's decision. `conwayToDijkstraSubLedgerPredFailure`
    (`SubLedger.hs:373-394`) calls 4 of Conway's LEDGER constructors impossible. Its instance here
    (`DijkstraSubLedger.ConwayFailure`) is over the 4 it maps whose payloads SUBLEDGER's injections take whole: CERTS's
    failure, GOV's, the accounts missing and the treasury. `ConwayUtxowFailure` is left out too, by the decision that
    left UTXOW's `UtxoFailure` out of SUBUTXOW's (2026-11-21, #85): its payload, Dijkstra's UTXOW failure, goes through
    SUBUTXOW's injection, which the reference leaves partial, and no predicate SUBLEDGER runs reports it.
  - Evidence:
    - `DijkstraSubLedgerSuite`, on the testnet's chain (`fixtures/reference/leios-testnet-chain-contexts`): of the 38
      sub-transactions of its 28 batches, 3 withdraw, 2 register an account and none names the treasury, votes,
      proposes, deposits directly or donates. From the recorded outputs and the empty certificate state, SUBLEDGERS
      refuses the 3 batches whose sub-transactions withdraw, each with SUBENTITIES' accounts missing from the current
      and the original accounts, and admits the other 25. From that state with the withdrawn accounts registered, the
      least state the withdrawals need, it admits all 28: every sub-transaction's inputs spent and outputs created in
      order, the instant stake moved with them, the fees, deposits, donation and governance state as they were, the
      whole certificate state the one before with the accounts withdrawn from drained and those registered added with
      their deposits of 2,000,000 lovelace, the proposals as they were, and the events, in one ordered sequence, of
      each sub-transaction in turn: SUBGOV's two, by its id, with no proposal and no vote, then SUBUTXOW's outputs
      spent and created. D7a's UTXOW then admits each top level on
      the state SUBLEDGERS leaves, with the pools after it and the certificate state before the transaction
      (`Ledger.hs:476-485`), every level's inputs spent and outputs created.
    - `DijkstraSubLedgerSuite`, on crafted cases: a sub-transaction that names a treasury other than the chain's is
      refused with `SubTreasuryValueMismatch` with the top level flagged either way, and after SUBUTXOW's block when
      its inputs are spent; with the chain's treasury and the top level flagged invalid, its spent inputs are still
      SUBUTXOW's refusal; flagged invalid, a sub-transaction that withdraws from accounts the state lacks is admitted
      and changes nothing; a sub-transaction that registers the script account its proposal's deposit returns to, from
      a state without it, is admitted and its proposal added by the sub-transaction's id, so SUBGOV reads the
      certificate state after SUBENTITIES; without the registration it is GOV's `ProposalReturnAccountDoesNotExist`,
      and with another deposit `ProposalDepositIncorrect`, each in SUBGOV's newtype; flagged invalid, neither runs; the
      proposal's epoch is the slot's on the testnet's 21,600-slot epochs where the environment gives none, and a
      distinct epoch the environment gives otherwise; a committee update one
      sub-transaction proposes, beside an information action, is the committee's one proposal by its id
      (`proposalsWithPurpose`, and in the node's record), and the next sub-transaction's authorisation of a hot key
      for the member it adds is admitted, and refused as `DijkstraCommitteeIsUnknown` without it; a registering
      sub-transaction given twice is refused the second time with SUBENTITIES' block, then SUBUTXOW's of its spent
      inputs; two sub-transactions given twice, the second's block reported first at the root; SUBLEDGERS on one
      sub-transaction given 2000 times on a small stack (STK-3).
    - `DijkstraGovSuite`: on each of Conway's 19 GOV failures, built, SUBGOV's injections are the newtype after GOV's;
      on the chain's proposals, admitted and refused, SUBGOV's proposals, events and failures are GOV's in its
      newtypes.
    - `DijkstraSubLedgerSuite`, on built values: each of SUBLEDGER's and SUBLEDGERS' injections is the reference's
      composition; the four constructors the reference calls impossible, `ConwayUtxowFailure` and the whole type have
      no instance (`compileErrors`, beside a reachable one that has).
    - D7a's, D7b's and D7d's Dijkstra suites, Conway's era suites and the node's Conway and upstream suites keep their
      expected values.
  - Incomplete (CNF-2):
    - the batches as the reference judged them: the fixture records neither the chain's certificate state nor its
      proposals, so the accounts registered are the least the withdrawals need, and the verdicts there are the rules';
    - SUBUTXOW given the certificate state from before SUBENTITIES, threaded from the earlier sub-transactions: no
      check of SUBUTXOW or SUBUTXO reads it at 9df9f98c (`getWitsVKeyNeeded _ = getConwayWitsVKeyNeeded`,
      `Dijkstra/UTxO.hs:161`, and `sueCertState` is read nowhere else), so no verdict can show it; the threading of
      the state SUBLEDGER returns is shown instead;
    - refusals that no recording holds: any of SUBLEDGER's, SUBLEDGERS' or SUBGOV's by the reference, and a
      sub-transaction's votes and proposals but the crafted ones;
    - the failures are not compared with the reference's bytes: the era failures have no encoder yet (#796).
  - Not yet (#85): LEDGER (D7e), which runs SUBLEDGERS before the top level's rules.
- **Next, Conway's rules.** The first rule ported is one that needs no parameters, the second one that
  does, each under a differential against the flat rule on the recorded blocks and the refusal corpus.
- The body is read through the flat decoder for now. What only the projection refuses (an update, genesis
  delegation, instantaneous rewards) the flat Conway decoder refuses first, with the same error, as tests on
  crafted bytes show.
- The spike's Babbage body is removed, so no partial body remains, and Babbage keeps its output and script
  types. A function written once over the body classes (`EraTxSuite`) runs in Conway alone; Dijkstra's body is
  typed (above), and its rules come in stage 3.

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
