# Changelog fragments

A change that a user, an operator or another implementer could notice adds one file here instead of
editing `CHANGELOG.md`, so that branches landing in parallel do not conflict.

- Name: `<issue>-<short-slug>.md` (for example `130-follower-stall.md`), or the branch name when there
  is no issue.
- Content: the entry as it will appear in the changelog, starting with a heading of the form
  `### Added|Changed|Fixed|Removed (<topic>, #<issue>)` and its bullets, in the style of
  [CHANGELOG.md](../CHANGELOG.md). A change with no issue leaves out the `#<issue>`.
- A fragment holds one or more such sections, each with at least one bullet, and nothing else (no front matter,
  no top-level title). It is a level 2 or higher deliverable ([CONTRIBUTING.md](../CONTRIBUTING.md#changelog-fragments));
  a level 1 change adds one only if a user could notice.
- Stable release preparation folds the selected fragments into a version section of `CHANGELOG.md`
  and deletes only those exact, hash-matching inputs in the same reviewed PR. Beta notes retain
  the fragments. Until stable consolidation, fragments are the unreleased changes.

## Consolidation tooling

The [preparation guide](../docs/changelog-preparation.md) describes the pinned Scriv adapter,
immutable selection, preview, stable application and interrupted-transaction recovery. The
[release design](../docs/design/releases.md#changelog-fragments-and-consolidation) remains the
publication/promotion contract; this tool does not publish or authenticate a release.

Use `scripts/release_fragments.py preview` with explicit source, externally verified published
stable baseline, version/date and a fresh scratch output directory. Review its exact selected
paths/hashes and rendered changelog/notes. Beta previews retain fragments. Stable `apply` updates
`CHANGELOG.md` and deletes only selected, hash-matching inputs in a reviewed preparation PR.
New/deferred files remain; changed/missing inputs fail. Existing legacy Unreleased text is included
once; repeated application cannot duplicate a version. Keep nested body headings (for example
`#### Known gaps`) under a valid topic section, with at least one bullet in each topic section.

Retained `.release/changelog/` receipts are preparation records, never publication receipts.
A pending stable attempt blocks another stable attempt; beta previews recover its consumed inputs
from immutable Git alongside new fragments. Only the trusted coordinator may supply a new verified
published baseline after actual publication/verification. Reviewed abandonment restores originals,
reserves the old version, and does not move/reuse tags. See the guide for exact limits and commands.
