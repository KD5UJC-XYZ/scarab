# Design documents

Approved plans for work too large for one change, split into stages small enough for one session each.
Each starts with a status line ([docs/process.md](../process.md#documents-have-a-status)); the
decisions they rest on are [ADRs](../adr/README.md).

| Document | Status | Issue |
|---|---|---|
| [Releases and repository hardening](releases.md) | Owner decisions accepted; implementation staged | [#161](https://github.com/KD5UJC-XYZ/scarab/issues/161), [#75](https://github.com/KD5UJC-XYZ/scarab/issues/75), [#76](https://github.com/KD5UJC-XYZ/scarab/issues/76) |
| [SCLS binary interchange](scls-interchange.md) | Current; pinned profile, complete-base offline import/export and CLI implemented; full check, extended fuzz and restart passed | [#77](https://github.com/KD5UJC-XYZ/scarab/issues/77) |
| [Multi-script Plutus results](script-results.md) | As built for aggregation/reconstruction; diagnostic presentation remains open | [#195](https://github.com/KD5UJC-XYZ/scarab/issues/195) |
| [Bounded parallel Plutus](parallel-plutus.md) | As built; broader D2 acceptance remains open | [#21](https://github.com/KD5UJC-XYZ/scarab/issues/21) |
| [Incremental reward calculation](reward-pulser.md) | Implemented; typed resumable runtime and unchanged-reference oracle | [#135](https://github.com/KD5UJC-XYZ/scarab/issues/135) |
| [Domain and service errors](domain-errors.md) | As built for runtime contracts and declared-channel guard; follow-ups tracked | [#82](https://github.com/KD5UJC-XYZ/scarab/issues/82) |
| [Ledger-state mirror, step 1](ledger-mirror.md) | Historical (done 2026-10-15) | [#82](https://github.com/KD5UJC-XYZ/scarab/issues/82) |
| [Ledger-state mirror, step 2](ledger-mirror-step2.md) | Accepted; jumps 1 and 2 done (J2.9, 2026-10-17), jump 3 open | [#82](https://github.com/KD5UJC-XYZ/scarab/issues/82) |
| [Ouroboros Leios](../leios.md) | Proposal (research, not implemented) | [#70](https://github.com/KD5UJC-XYZ/scarab/issues/70) |
| [Memory-lean decoding](memory-lean-decoding.md) | Stages 0 to 3 implemented and measured, stage 4 a proposal | [#94](https://github.com/KD5UJC-XYZ/scarab/issues/94) |
| [Schema-first models](../models.md) | Spike (generator done; node adoption not started) | [#82](https://github.com/KD5UJC-XYZ/scarab/issues/82) |
| [Peer selection: the P2P governor](peer-selection.md) | As built; G1.1–G1.4 and original parent acceptance evidenced | [#31](https://github.com/KD5UJC-XYZ/scarab/issues/31) |
| [Network resource limits](network-limits.md) | As built; reference state/queue/SDU matrix and explicit local profiles | [#35](https://github.com/KD5UJC-XYZ/scarab/issues/35) |
| [Haskell snapshot import and export](haskell-snapshots.md) | Accepted (D1–D9); S1–S5 complete, including recorded preprod acceptance | [#140](https://github.com/KD5UJC-XYZ/scarab/issues/140), [#143](https://github.com/KD5UJC-XYZ/scarab/issues/143) |
| [Certified Mithril bootstrap](mithril-bootstrap.md) | Implemented; recorded-Preview acceptance and local integration gates complete | [#27](https://github.com/KD5UJC-XYZ/scarab/issues/27) |
| [Deterministic adversarial schedules](adversarial-schedules.md) | As built; finite fixed-seed regression schedules, not a security proof | [#78](https://github.com/KD5UJC-XYZ/scarab/issues/78) |
