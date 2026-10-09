# Internal checked native ledger-side join

`NativeLedgerSeed.bind` joins the existing checked epoch components, normalized
governance and exact parameter/global bridges. It retains their decoded objects
and requires one original seed, debug epoch, whole UTxO, point and source identity.
It is an internal diagnostic result, with no runtime import or admission capability.

The join compares shared original component bytes and hashes, account domains and
reward/deposit/pool projections, votes and reverse delegation, pools and snapshots,
deposit obligations and supply, all four temporal parameter originals, exact reward
Absent and non-myopic state. Existing bounded decoders and the pure finite governance
validator supply these checks; the join does not introduce a second native parser.
The governance validator is used for consistency, not as evidence that a boundary
was executed against the audited seed.

The audited bundle retains outer current C / previous P and historical current P /
previous P with NoUpdate. The historical empty DRep snapshot stays distinct from
the three current registrations. No original is rewritten to manufacture agreement.

## Remaining crossing blockers

The actual bundle reports exactly missing point-bound protocol-v2 evidence and
incompatible crossing geometry. Its epoch length is 500, security parameter 5 and
active-slot coefficient 0.05; the randomness window is 400, so the required
`2 * randomnessWindow < epochLength` condition fails. The constructor cannot clear
the protocol blocker or enable crossing. A separately checked fresh capture remains
necessary; there is no protocol supervisor or permission change in this work.

Reward state is explicitly Absent, so no historical reward freeze is required or
fabricated. Source authentication, complete parameter/cost-model validity and live
governance cursor recovery remain standing limitations, separate from this blocker
list. Hashes bind supplied sources; they do not authenticate them.

## Verification

Default generated fixtures exercise valid joins and reject coherent source splices
and overlap mismatches with individually valid hashes. Opt-in tests use the seven
hash-pinned audited originals mounted read-only, assert the exact blockers and
temporal roles, and reject a foreign whole-source governance object even though its
component spans and recomputed hashes are valid. Original captures remain private.

Final offline verification passed **119 Scala tests** (94 app, 25 ledger),
including both explicitly enabled audited join tests and the five existing audited
component tests, plus formatting checks. The run used 2 CPU/2 GiB, no network, a
private cache and only seven immutable original files mounted read-only. All seven
external hashes were checked before and after execution; owned container cleanup
was confirmed. No native executable, cluster or protocol supervisor was run.

Test log SHA256: `dd20d7e0037f02744dea95696e10bd33176f9fcccb5368c8de8d72729729c2f3`.
