# 0010. Scala 3.3 LTS

- **Status**: Superseded by [0026](0026-scala-3-9.md) ([amended 2026-10-30](#amendment-2026-10-30-superseded-by-scala-39-adr-0026))
- **Date**: 2026-10-14

## Context

Scala 3 offers the type-level features this design relies on (match types, opaque types, enums as
GADTs, givens). Newer minor versions add features but move faster.

## Decision

~~Stay on the Scala 3.3 long-term-support line "for now" (currently 3.3.8), with `-Werror` and the unused
warnings on. Revisit when a needed feature or a dependency requires a newer line.~~ ([amended 2026-10-30](#amendment-2026-10-30-superseded-by-scala-39-adr-0026))

## Consequences

- Some newer syntax is unavailable (for example `-Wsafe-init` is spelled `-Ysafe-init`).
- Dependencies are chosen for 3.3 compatibility.

## Amendment (2026-10-30): superseded by Scala 3.9 (ADR 0026)

The build moved to Scala 3.9.0 (owner decision, 2026-10-30, #649); [ADR 0026](0026-scala-3-9.md)
records the move. The flag this record named is now spelled `-Wsafe-init`.
