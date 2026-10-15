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
