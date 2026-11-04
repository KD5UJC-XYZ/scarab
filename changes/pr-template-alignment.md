### Changed (process)

- The pull request template carries the "Deviations from the reference" and "Gaps" sections at every level the policy check reads them, states the reference rule in its guidance, and ends with the closing-line reminder (`Closes #N` or `Related to #N; this PR leaves the issue open: <reason>`). The non-closing wording avoids a closing keyword next to an issue reference, which GitHub treats as a closing link even when the sentence negates it.
- `scripts/pr_policy.py` requires both sections from level 1 up, not level 2, and reports an empty section as missing: every pull request that changes a non-documentation file states its stance against the Haskell reference before it can merge.
- CONTRIBUTING.md and the decisions register record the extension (owner decision, 2026-11-04).
