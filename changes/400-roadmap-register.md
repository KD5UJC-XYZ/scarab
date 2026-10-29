### Changed (roadmap register, #400)
- **`docs/roadmap.md` is generated from the register `docs/data/roadmap.json`** by `scripts/render_roadmap.py`: one
  entry per item with a stable id, a status from a closed set (planned, in progress, done, declined, folded,
  decision), its issues, priority and prose. CI runs `--check`, which fails on a hand edit and on an item whose
  status disagrees with its issues' state on GitHub (a done item with an open issue, an open item whose issue is
  closed); a pull request's closing keywords count as closed. The migration marks the items whose issues had
  already closed, folds the Leios gaps X1 to X13 into #73, and rewrites the order of work after the v0.2 gate.
