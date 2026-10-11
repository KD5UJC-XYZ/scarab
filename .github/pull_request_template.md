## What and why

<!-- Reference roadmap IDs (e.g. B2, F1) and issues. -->

## Gates run (see CONTRIBUTING.md)

- [ ] `scripts/sbtw check` passes
- [ ] Ledger/consensus change: `devnet.py soak` and `e2e` run (commit, result):
- [ ] Network change: `soak` and `relay` run:
- [ ] Storage change: restart check with `--db` and `soak`:
- [ ] Forging/mempool change: `pool` and `relay` run:
- [ ] Not applicable (docs or tooling only)

## Evidence and docs

- [ ] New behaviour has tests or properties; bugs found on real data have a regression fixture
- [ ] Compatibility matrix row updated (docs/compatibility.md), including "explicitly unchecked"; which row: 
- [ ] Roadmap item marked done or updated (docs/roadmap.md); IDs not renumbered
- [ ] Findings added if something was learned against real data (docs/findings.md)
- [ ] Relative links in changed docs resolve

## Hygiene

- [ ] No keys, secrets or run data committed; fixtures have provenance and licence recorded (NOTICE/README)
- [ ] Domain errors are closed ADTs through `Raise`; hashed bytes are the original bytes
