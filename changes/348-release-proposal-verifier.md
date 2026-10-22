### Changed (release proposals, #348)
- The release-proposal verifier compares the receipt's baseline authority with `release-please-authority.json` at the proposal
  source and refuses a mismatch. The pull-request control now runs on every pull request: a `release-please--branches--*` head
  must be same-repository and is fully verified, and any other branch that adds or changes proposal receipts is refused instead
  of being skipped.
- The live pull-request writer `scripts/release-please/github-pr.cjs` has tests against a stubbed GitHub API and a local bare
  remote: create, update, idempotent re-run and the refusal and error paths.
