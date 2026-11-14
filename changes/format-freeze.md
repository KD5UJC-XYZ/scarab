### Changed (persisted formats are frozen)

- **Persisted formats are frozen until Scarab leaves pre-release.** Deployed stores use snapshot format V8; no
  format version, schema, fingerprint, magic, migration step, regenerated snapshot fixture or in-place edit of an
  existing version changes without the owner's explicit approval, asked for and given in the chat session doing the
  work (FMT-1, docs/design-rules.md). A new guard, `scripts/check_storage_format.py`, run by `scripts/preflight` and
  CI, fails with `STORAGE FORMAT CHANGE` on any change to the persisted-format surface recorded in
  `docs/data/storage-format.json`, and the pull request policy requires an approved change to be a pull request of
  its own, titled `<type>(storage)!: …`, with the question and the answer and their UTC times.
- **No rule depends on a GitHub account.** Approval is the owner's explicit answer in the chat session doing the
  work; a tracked record of it is evidence, never approval.
