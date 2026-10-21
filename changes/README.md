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

## Consolidation plan

The [release design](../docs/design/releases.md#changelog-fragments-and-consolidation) specifies
news-fragment collection, a known pattern supported by Scriv/Towncrier. No collector is installed
yet. The first implementation trials the existing Markdown corpus without losing prose, including
legacy fragments needing structural repair and the old `CHANGELOG.md` Unreleased entries.

The release PR freezes the source/version/date and records eligible paths and hashes; validates
headings and bullets; renders deterministic issue/path order; updates the changelog and matching
release notes; then removes only the selected stable-release fragments. New or changed inputs
require regeneration/review. The README and unselected files remain. Repeated collection cannot
create duplicate sections. Git history and the release receipt retain the consumed inputs.

Conventional commits propose version increments; fragments supply the human-written notes.
Release-please must use the reviewed fragment output instead of a competing commit-log changelog.
Stable preparation lands through `dev` -> `main` before promotion to `release/prod`; beta snapshots
are cumulative from the previous successfully published/verified stable baseline and do not
consume the pending fragments. If stable publication stalls after consolidation, beta collection
also recovers consumed-but-unpublished entries from the retained source/receipt. Allow one pending
stable consolidation; abandonment requires reviewed restoration before the next consolidation.
This prevents a failed publication from making unreleased changes disappear from later notes.
