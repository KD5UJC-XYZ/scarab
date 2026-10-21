<!-- SPDX-License-Identifier: Apache-2.0 -->
# Preparing changelogs

**Current: local preparation tooling under [#161](https://github.com/KD5UJC-XYZ/scarab/issues/161).
Publication, tag authority, publisher authentication and repository protection remain separate.**

[`scripts/release_fragments.py`](../scripts/release_fragments.py) implements the fragment portion of
[the release design](design/releases.md#changelog-fragments-and-consolidation). It creates a reviewable
patch and records a pending preparation. It never commits, stages Git files, creates tags, publishes,
checks a network release, or declares the v0.2 acceptance gates complete. Target `main` during the
current transition; the future promotion policy does not take effect through this tool.

## Install and input authority

Use Linux/amd64 and Python 3.12 or 3.14. The complete version/hash lock includes Scriv 1.8.0 and its
transitive wheels for these interpreters. CI runs the same installation in an isolated environment:

```sh
python3 -m venv /tmp/scarab-changelog
/tmp/scarab-changelog/bin/pip install --require-hashes -r scripts/release/requirements.txt
/tmp/scarab-changelog/bin/python scripts/release_fragments.py --help
```

Supply a full lowercase source commit SHA, explicit plain SemVer proposal and ISO date, and a full
`--baseline` SHA identifying the last successfully published **and independently verified stable**
release. The future trusted coordinator/operator must authenticate that baseline and the approved
candidate. Full-SHA syntax, Git ancestry and a local receipt do not prove publication, authentication,
approval or compatibility. This tool does not infer a version from commit messages or choose a tag.
It remains independent of build-version metadata; the coordinator must reconcile both before release.

Selection reads regular tracked Git blobs at `--source`, never dirty/untracked working files. By default
it selects all direct Markdown files in `changes/` except `README.md`, `TEMPLATE.md` and
`*.template.md`; nested template directories are outside that direct-file selection. A feature named
`161-template-support.md` remains eligible. Repeated `--select
changes/<name>.md` explicitly defers other entries. Numeric issue prefixes sort numerically, ties sort
by path, and nonnumeric names follow lexically. Each input retains its internal section order and
original text, with only surrounding newline separators standardized. Equal issue numbers or headings
do not merge entries. Hash identities already consumed by receipts contained in the declared published
baseline are excluded; a changed fragment at the same path is a distinct input.

New/changed malformed fragments fail with path and line: top-level topic sections must use Added,
Changed, Fixed or Removed and contain a bullet. Nested body headings, paragraphs and fenced code are
retained. Links should also work from the root changelog and GitHub release notes; absolute URLs
pinned to the relevant source revision are appropriate for historical references. Three existing
relative destinations were pinned without changing their labels or prose. The initial 25 legacy structural repairs add only headings/bullet markers or make Known gaps
nested headings; they do not shorten the historical explanations. The existing legacy Unreleased
sections in `CHANGELOG.md` are captured and moved exactly once; its introductory prose and previously
published sections remain. Their historical claims still require human release review.

## Preview, review and stable application

Run these with approved actual commit identities, not the illustrative shell placeholders:

```sh
/tmp/scarab-changelog/bin/python scripts/release_fragments.py preview \
  --source "$candidate_commit" --baseline "$verified_stable_commit" \
  --version 0.2.0-beta.1 --date 2026-10-21 --output /tmp/scarab-beta-notes
```

The exclusive new output directory contains `plan.json`, `notes.md` and a proposed `CHANGELOG.md`.
No tracked input changes. Beta previews retain fragments and cannot be applied. Keep each beta note
snapshot separately; no stable changelog section is inserted by a beta preview. To prepare stable
consolidation, regenerate with the approved stable version and a new output directory, review the
selection and both rendered outputs, then apply that exact plan:

```sh
/tmp/scarab-changelog/bin/python scripts/release_fragments.py apply /tmp/scarab-stable-notes/plan.json
/tmp/scarab-changelog/bin/python scripts/release_fragments.py check .release/changelog/0.2.0.json
```

Stable application re-derives the plan from immutable Git, verifies both reviewed output files and
current selected input hashes, updates the changelog, deletes only the exact selected inputs, and
writes `.release/changelog/<version>.md` plus its JSON preparation receipt in the same patch. Newly
arrived/deferred fragments remain. A changed/missing selected file or modified changelog/output
refuses application. Review the complete patch in a PR. The tool does not grant release approval.

The schemaVersion 1 receipt binds collector version/adapter, proposed version/date/channel, source,
declared baseline, ordered input source/path/SHA-256 identities, legacy source/hash, output hashes and
plan hash. State `pending-publication` means only that this patch was prepared. It is not a publication
receipt. Repeating an identical application is a no-op; a different plan cannot reuse a reserved version.

## Pending publication and recovery

At most one stable preparation after the declared published baseline may remain pending. Until
publication actually succeeds, keep using the old verified baseline: a new stable attempt is refused,
while beta preview recovers consumed-but-unpublished fragments and legacy prose from their original
source/path/hash identities and includes new fragments. It does not read only surviving files.

After independent publication verification, the coordinator may supply the newly verified stable
commit as baseline. Identical retained receipts already in that baseline become published-range
history, without rewriting their original preparation state or claiming that this local tool verified
publication. A same-name but changed receipt is refused. The next stable preparation retains all old
published sections and excludes previously consumed identities. There is no `mark-published` command.

If an unpublished attempt is abandoned, prepare a reviewed restoration instead:

```sh
/tmp/scarab-changelog/bin/python scripts/release_fragments.py restore \
  .release/changelog/0.2.0.json --baseline "$verified_stable_commit"
```

Restoration requires unchanged prepared outputs and no reappeared selected inputs; it restores their
original bytes and pre-preparation changelog and records `restored`. Receipt/notes stay retained and
the version stays reserved. A preparation contained in the declared published baseline cannot be
abandoned. No reserved tag may be moved/reused; this tool does not operate on tags.

A nonblocking Git-worktree lock serializes these commands. Before mutations, a journal inside the
Git worktree metadata records source HEAD and exact before/after hashes and payloads. Writes replace
individual files; a multi-file patch is not atomic. After a process interruption, keep the same HEAD
and run `recover` to roll forward. Recovery checks every operation before writing, accepts only its
recorded before/after states, rejects unrelated/duplicate paths or schema changes, and stops on unknown
edits or changed HEAD for manual review. It cannot arbitrate an external editor ignoring the lock;
freeze the checkout during apply/recover. This is process-interruption recovery, not a claim of a
filesystem-wide transaction or authenticated journal.

## Why the Scriv adapter exists

[Scriv 1.8.0](https://scriv.readthedocs.io/en/1.8.0/commands.html) was trialled on all 119 fragments at
`fadf4ae2363e2986ab620ee6ff2fcc8e59ab7948`, with categories disabled and Markdown heading level 2.
Only 83 whole source blocks remained contiguous after stock collection: its parser merges identical
headings, groups uncategorized prose, strips comments and normalizes paragraphs. The narrow adapter
overrides the public `sections_from_fragment` hook so each source is one uncategorized block; Scriv's
public `combine_fragments` and Markdown formatter still collect/render it. It does not monkey-patch
Scriv or substitute a competing commit-log collector. Tests require the exact concatenation of every
actual corpus block and protect comments, nested headings and code. Selection, transaction safety and
publication boundaries are the repository-specific orchestration around Scriv.

Reproduce the original stock/adapter trial without changing real inputs:

```sh
/tmp/scarab-changelog/bin/python scripts/release_fragments.py trial \
  --source fadf4ae2363e2986ab620ee6ff2fcc8e59ab7948 --output /tmp/scarab-scriv-trial
```

The trial retains every input hash, stock/adapted output hashes and the losslessness assertion.
Towncrier's typed-filename pattern was considered; migrating the accumulated topic-heading corpus to
that format adds work without resolving these byte-preservation requirements. No real release was
prepared, no current fragments consumed, and `CHANGELOG.md` remains untouched by this implementation PR.
