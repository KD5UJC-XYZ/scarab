# 0010. Scala 3.3 LTS

- **Status**: Accepted
- **Date**: 2026-10-14

## Context

Scala 3 offers the type-level features this design relies on (match types, opaque types, enums as
GADTs, givens). Newer minor versions add features but move faster.

## Decision

Stay on the Scala 3.3 long-term-support line "for now" (currently 3.3.8), with `-Werror` and the unused
warnings on. Revisit when a needed feature or a dependency requires a newer line.

## Consequences

- Some newer syntax is unavailable (for example `-Wsafe-init` is spelled `-Ysafe-init`).
- Dependencies are chosen for 3.3 compatibility.
