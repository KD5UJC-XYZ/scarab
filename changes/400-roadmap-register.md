### Changed (roadmap register, #400)
- **`docs/roadmap.md` is generated from the register `docs/data/roadmap.json`** by `scripts/render_roadmap.py`: one
  entry per item with a stable id, its issues, priority and prose. An item's status (planned, in progress, done,
  declined) comes from its issues through the snapshot `docs/data/issue-states.json`, so it cannot drift from them;
  only decision and folded items, and items without issues, store one. CI's `--check` is offline: it fails on a hand
  edit or an invalid register. The nightly triage workflow refreshes the snapshot and proposes the new render as a
  pull request. The migration marks the items whose issues had already closed, records that the Leios gaps X1 to
  X13 were folded into #73, and rewrites the order of work after the v0.2 gate.
