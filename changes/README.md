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
- A release folds every fragment into a version section of `CHANGELOG.md`, in issue order, and
  deletes them. Until then, the fragments are the unreleased changes.
