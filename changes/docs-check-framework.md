### Added (documentation drift check)
- **`scripts/check_docs.py`** checks `docs/**/*.md`, `README.md`, `AGENTS.md` and `CONTRIBUTING.md` for broken relative links and
  anchors, cited repo paths and `Type.member` symbols that no longer exist, line-number citations, per-file and per-table-cell
  size budgets, and upstream versions or commit ids that `docs/data/reference-pins.json` does not register. Today's
  violations are the baseline `docs/data/docs-check-baseline.json`; CI fails on new violations and on baseline entries that no
  longer occur (`--write-baseline` shrinks it), so the documentation can only get cleaner. It runs in the existing Python job,
  without sbt.
