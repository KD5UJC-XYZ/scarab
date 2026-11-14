# 0007. Era-indexed state: phantom eras, a closed witness and match types

- **Status**: Accepted
- **Date**: 2026-10-15
- **Design**: [ledger-state mirror](../design/ledger-mirror.md)

## Context

Haskell's ledger indexes its state by era with type families (`CertState era`, `GovState era`, …), so a
rule for one era cannot touch another era's data. A spike checked whether Scala 3.3 can express the same.

## Decision

Eras are phantom types; `ShelleyBasedEra[E]` is a closed enum witness; era-dependent parts are match
types (`CertState[E]`, `GovState[E]`, …) that reduce under the witness's GADT refinement, with no casts.
Conventions the encoding imposes: the era is given explicitly to `apply`/`copy` of era-generic case
classes; `Eq`/`Show` of era-generic composites are hand-written over the witness; one Monocle `Focus` per
concrete case class, composed with `andThen`; one match-type case per era.

## Consequences

- Reading another era's field, or calling an era's rule with another era's state, does not compile.
- Adding an era (Dijkstra) makes the compiler list every place that must handle it.
- The same pattern extends to consensus and transactions ([#85](https://github.com/KD5UJC-XYZ/scarab/issues/85)).

## Amendment (2026-11-14): era-specific types, no inter-era generics

Decided by the owner on 2026-11-14 for the era-typing work ([#85](https://github.com/KD5UJC-XYZ/scarab/issues/85))
and forging in every Shelley-based era ([#544](https://github.com/KD5UJC-XYZ/scarab/issues/544)).

1. **What stays.** A type or container that keeps an operation inside one era is the encoding this ADR
   chose: phantom eras, match types with one case per era, and `EraNes.InEra[E]`, which holds the state of
   the era the chain is in with that era's witness.
2. **No inter-era generics.** A type whose meaning changes with the era is not used. That rules out:
   - evidence that lets one implementation stand for several eras (`PreConway[E]`);
   - grouped shape witnesses (`ShelleyShapes`, `ConwayShapes`, `EraShape`);
   - behaviour chosen by comparing eras (`ShelleyBasedEra.atLeast`);
   - one record for every era's transaction, whose fields are optional by era (`TxBody`, `Witnesses`, `Tx`,
     `TxOut`).
3. **Behaviour from the reference's classes.** Era-dependent behaviour comes from type classes named after
   the reference's (cardano-ledger f649f975: `ShelleyEraTxBody`, `AllegraEraTxBody`, `AlonzoEraTxBody`,
   `AlonzoEraTxWits`, `BabbageEraTxBody`, `BabbageEraTxOut`, `ConwayEraTxBody` and the rest). Each has an
   instance at exactly the eras where the reference has one. A rule asks for the capability it uses, so an
   era without it does not compile.
4. **Transactions per era.** Transaction bodies, witness sets, auxiliary data, outputs and whole
   transactions are types of their era, as the reference's are, so a transaction of one era cannot be given
   where another era's is expected. Outputs are included. Their stored bytes and schema fingerprints are kept,
   or changed only as [ADR 0006](0006-versioned-formats-migrate-on-open.md) allows.
5. **The witness is for unpacking and routing.** `ShelleyBasedEra[E]` stays as the runtime proof of an era.
   It unpacks an era known only at run time (an era tag read from bytes, a snapshot, a hard fork), and an
   exhaustive match on it routes to that era's own code. It is never used to compare eras.
6. **Consensus parameters per era.** Consensus parameters are given per era, as the reference gives a
   `PartialConsensusConfig` per era. One example is the window after which the candidate nonce is frozen:
   the stability window (3k/f) up to Babbage, and the randomness stabilisation window (4k/f) in Conway
   (ouroboros-consensus 82ecba329 `ouroboros-consensus-cardano/src/ouroboros-consensus-cardano/Ouroboros/Consensus/Cardano/Node.hs:787-799`).
   The two windows are distinct types, so an era's header cannot be checked with another era's window.
7. **What a type cannot carry stays a runtime check:**
   - a protocol-version gate inside an era (the reference's `hardfork*` predicates);
   - a check that depends on the network;
   - matching an era tag read from bytes against the era the chain is in.
8. **Open, for a later amendment:**
   - how a type the reference shares across eras with the era as a parameter is mirrored (`ShelleyTxOut era`
     for Shelley, Allegra and Mary);
   - how a rule the reference shares across eras is mirrored (Mary's UTXO is Allegra's);
   - whether certificates become per era in this work.

The work lands on one feature branch and reaches `main` stage by stage. The accumulation of predicate
failures in the reference's order ([#412](https://github.com/KD5UJC-XYZ/scarab/issues/412)) is done with the
rule rewrite.
