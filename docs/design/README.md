# Design documents

Approved plans for work too large for one change, split into stages small enough for one session each.
Each starts with a status line ([docs/process.md](../process.md#documents-have-a-status)); the
decisions they rest on are [ADRs](../adr/README.md).

| Document | Status | Issue |
|---|---|---|
| [Ledger-state mirror, step 1](ledger-mirror.md) | Historical (done 2026-10-15) | [#82](https://github.com/KD5UJC-XYZ/scarab/issues/82) |
| [Ledger-state mirror, step 2](ledger-mirror-step2.md) | Accepted; jumps 1 and 2 done (J2.9, 2026-10-17), jump 3 open | [#82](https://github.com/KD5UJC-XYZ/scarab/issues/82) |
| [Ouroboros Leios](../leios.md) | Proposal (research, not implemented) | [#70](https://github.com/KD5UJC-XYZ/scarab/issues/70) |
| [Memory-lean decoding](memory-lean-decoding.md) | Stages 0 to 3 implemented and measured, stage 4 a proposal | [#94](https://github.com/KD5UJC-XYZ/scarab/issues/94) |
| [Schema-first models](../models.md) | Spike (generator done; node adoption not started) | [#82](https://github.com/KD5UJC-XYZ/scarab/issues/82) |
| [Peer selection: the P2P governor](peer-selection.md) | Accepted; G1.1–G1.4 implemented and reference checked; parent acceptance reviewed separately | [#31](https://github.com/KD5UJC-XYZ/scarab/issues/31) |
| [Ledger snapshots in the Haskell node's layout (Step D)](haskell-snapshots.md) | Proposal; stage S1 (MemPack `TxOut`) built | [#140](https://github.com/KD5UJC-XYZ/scarab/issues/140), [#143](https://github.com/KD5UJC-XYZ/scarab/issues/143) |
| [Haskell snapshot import and export](haskell-snapshots.md) | Accepted (D1–D9); S1 implemented and measured, S2–S5 open | [#140](https://github.com/KD5UJC-XYZ/scarab/issues/140), [#143](https://github.com/KD5UJC-XYZ/scarab/issues/143) |
