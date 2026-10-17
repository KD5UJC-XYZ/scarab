# Changelog fragments

A change that a user, an operator or another implementer could notice adds one file here instead of
editing `CHANGELOG.md`, so that branches landing in parallel do not conflict.

- Name: `<issue>-<short-slug>.md` (for example `130-follower-stall.md`), or the branch name when there
  is no issue.
- Content: the entry as it will appear in the changelog, starting with a heading of the form
  `### Added|Changed|Fixed|Removed (<topic>, #<issue>)` and its bullets, in the style of
  [CHANGELOG.md](../CHANGELOG.md).
- A release folds every fragment into a version section of `CHANGELOG.md`, in issue order, and
  deletes them. Until then, the fragments are the unreleased changes.
