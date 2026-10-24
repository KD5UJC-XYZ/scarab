# 0002. Tagless final with cats-mtl capabilities and closed error types

- **Status**: Accepted
- **Date**: 2026-10-11 (refined 2026-10-15)

## Context

The node is meant to be read and reasoned about, with as many mistakes as possible caught by the
compiler. Effects (network, storage, time) must be swappable for tests and simulation.

## Decision

Algebras over `F[_]` at every effect boundary; interpreters chosen in `Main`; `IO` only in `Main` and
tools (the JSON log, once listed here, is the `LogRouter` interpreter of [ADR 0014](0014-typed-log.md), polymorphic in `F`). Capabilities through cats-mtl: `Ask`/`Local` for environment, `Stateful` for
state, `Raise`/`Handle` for typed errors, `Tell` for accumulation. Domain errors are closed ADTs; no
`Either[String, …]`, no string-based control flow, no exceptions for domain errors. Rules are pure
functions over values. Type classes with extension syntax, givens, derivation, opaque types and type
parameters (roles, eras) carry as much of the correctness as the compiler can check. Algebras carry
cats-laws or ScalaCheck properties.

## Consequences

- Deterministic tests (`TestControl`) and interpreters for simulation (K9) are possible.
- Some boilerplate: hand-written instances where derivation cannot reach (era-generic composites).
- Hot paths are measured before an abstraction is added to them.
