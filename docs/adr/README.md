# Architecture decision records

Short records of the decisions that shape Scarab: the context, what was decided, and what follows
from it. A decision that is reversed is not edited away: a new record supersedes it, and the old
one says so. New records copy [the template](template.md) and take the next number.

| # | Decision | Status | Date |
|---|---|---|---|
| [0001](0001-haskell-node-as-reference.md) | The Haskell node is the reference design | Accepted | 2026-10-15 |
| [0002](0002-tagless-final-and-cats-mtl.md) | Tagless final with cats-mtl capabilities and closed error types | Accepted | 2026-10-11 |
| [0003](0003-plutus-through-scalus.md) | Plutus evaluation through Scalus, behind our own interface | Accepted | 2026-10-11 |
| [0004](0004-docker-only-build.md) | Build and run only in containers | Accepted | 2026-10-10 |
| [0005](0005-cbor-codec-and-scodec.md) | Our own byte-preserving CBOR codec; scodec only for read-once fixed layouts | Accepted | 2026-10-15 |
| [0006](0006-versioned-formats-migrate-on-open.md) | Persisted formats are versioned and migrate on open; never a forced replay | Accepted | 2026-10-15 |
| [0007](0007-era-indexed-state.md) | Era-indexed state: phantom eras, a closed witness and match types | Accepted | 2026-10-15 |
| [0008](0008-networking-as-implemented.md) | Networking stays as implemented, not mirrored from Haskell | Accepted | 2026-10-15 |
| [0009](0009-ledger-mirror-step-2.md) | Ledger mirror step 2: three jumps behind a differential harness | Accepted | 2026-10-15 |
| [0010](0010-scala-3-3-lts.md) | Scala 3.3 LTS | Accepted | 2026-10-14 |
| [0011](0011-stack-safety.md) | Recursion over data from outside is stack safe | Accepted | 2026-10-16 |
| [0012](0012-portable-blst.md) | One portable libblst for every x86-64 CPU, shipped where blst-java loads it | Accepted | 2026-10-16 |
| [0013](0013-cbor-documents-are-an-index-over-the-input.md) | CBOR documents are an index over the input | Accepted | 2026-10-16 |
| [0014](0014-typed-log.md) | A typed log: `Tell` of lines built from typed events, routed by namespace and severity | Accepted | 2026-10-16 |
| [0015](0015-kes-key-written-back-and-checked-reload.md) | The evolved KES key is written back, and credentials are reloaded on SIGHUP only after a check | Accepted | 2026-10-17 |
