<!-- SPDX-License-Identifier: Apache-2.0 -->
# Release PR preparation

**Current:** PR preparation is implemented; automation writes remain disabled pending owner setup.
Tracks [#161](https://github.com/KD5UJC-XYZ/scarab/issues/161). No environment, App, branch protection,
release tag or publication was configured by this implementation. The broader
[release design](design/releases.md) and publication acceptance gates still apply.

## One proposal path

[The workflow](../.github/workflows/release-pr-preparation.yml) runs an unprivileged
`release proposal controls` job for every PR to the six planned protected targets and every push to
main. Manual runs can generate a preview or, after separate protected approval, create/update one
preparation PR. Both paths execute the same coordinator and pinned release-please **17.11.2** library.
Node **24.22.1**, the npm dependency lock, and the existing hash-pinned Scriv environment are explicit.
There is no release-please action that could separately publish a release. The Node pin was checked
against the [official LTS security release](https://nodejs.org/en/blog/release/v24.22.1).

The coordinator uses public `Manifest.fromManifest`, versioning/changelog registration hooks and
manifest plugins. The small SCM implementation supplies the explicitly frozen local Git range;
GitHub release/tag discovery does not silently redefine the reviewed baseline. Release-please
builds the conventional-commit proposal and owns its PR create/update decision. Its stock
`simple` version-file and changelog updates are replaced before any write: the manifest is planning
state only, and [the existing fragment collector](changelog-preparation.md) is the sole renderer.
No competing commit-log prose or extra `VERSION` file is emitted.

The configured pre-1.0 policy advances the minor for breaking changes and compatible features,
and the patch for fixes. After 1.0, breaking changes advance the major. Maintenance-only proposals
require explicit review through the `maintenance` input. Commit `Release-As` trailers are refused.

| Channel | Version example | Fragment treatment |
|---|---|---|
| alpha | `0.2.0-alpha.0` | Retain; cumulative snapshot |
| beta | `0.2.0-beta.0` | Retain; cumulative snapshot |
| preprod | `0.2.0-rc.0` | Retain; cumulative snapshot |
| prod | `0.2.0` | Checked stable consolidation in the preparation PR |

The explicit ordinal must be canonical and nonnegative. The selected channel is maturity, not a
Cardano network. The trusted `SCARAB_PROMOTION_POLICY` chooses `main` during `transition`, or `dev`
after `dev-only-main` cutover; the requester cannot choose an arbitrary integration target. The
control plane always executes reviewed workflow code from the trusted main ref, not scripts from
the proposed source. Candidate Git objects are read as data; only the trusted collector executes.

## Baseline authority and first use

[release-please-authority.json](../release-please-authority.json) deliberately has a null source.
The owner must review and commit an explicit baseline before the manual path can proceed.
The initial manifest version is a proposal seed; it does not assert that a corresponding release
has been published.

A first-use authority has `schemaVersion: 1`, `kind: "bootstrap"`, a full lowercase 40 `source`, a
canonical stable `version`, and a nonempty `reason` explicitly identifying an unpublished planning
baseline. Bootstrap cannot account for a source already containing prior preparation receipts.
Where the baseline predates the manifest, the configured `initial-version` is the initial marker.
Otherwise its exact tracked manifest must match. Do not advance bootstrap to make pending work
look published.

Later authority uses `kind: "published-stable"`, exact published source/version, and a `verification`
object containing `receiptPath` and `receiptSha256`. Retain the **original** independently produced
verification JSON under `.release/baselines/` in a subsequent reviewed preparation-control commit;
it cannot be retroactively added to the published source it verifies. The wrapper pins those exact
bytes, then invokes the shared [published receipt verifier](../scripts/published_receipt.py) using
the checksum-pinned GitHub CLI. That verifier requires completed public checks and authenticates
an attestation online against the expected repository, workflow, tag ref and source/signer commits.
A label, supplied success boolean, unsigned JSON, staging receipt or offline name-only claim is
not a published baseline. No genuine Scarab published-verification receipt exists from these tests.

The collector retains its one-pending-stable rule. Prerelease previews recover consumed but
unpublished entries from the original source/receipt, unioned with new fragments. To abandon a
stable preparation, use the collector's reviewed `restore` operation; its version remains reserved.
An explicit `next_version` recovery override needs `override_reason`, must match channel and ordinal,
and cannot lower the conventional proposal. It still cannot reuse a reserved version. This allows
reviewed recovery without claiming the abandoned attempt was published.

## Local preview

Install tools separately from a preview. These commands do not create remote resources:

```sh
npm ci --prefix scripts/release-please --ignore-scripts
python3 -m venv /tmp/scarab-release-python
/tmp/scarab-release-python/bin/pip install --require-hashes -r scripts/release/requirements.txt
python3 scripts/install_publication_tools.py --output /tmp/scarab-release-tools
node scripts/release-please/run.cjs --source "$reviewed_source" --channel preprod --ordinal 0 \
  --date 2026-10-21 --python /tmp/scarab-release-python/bin/python \
  --gh /tmp/scarab-release-tools/gh --output /tmp/scarab-proposal-preview
```

The reviewed authority file must already be valid. `--validate` checks the reviewed input authority
without a PR write. A successful preview contains `proposal.json`, the complete `patch.json` with
explicit deletions, and `result.json`; the working checkout remains unchanged. The collector runs its
real checked stable apply in a disposable sparse checkout, then returns the exact patch. It does not
consolidate the user's current checkout during a preview.

## Owner setup before enabling writes

1. Create a dedicated GitHub App, install it on **this repository only**, and grant **Contents: write**,
   **Pull requests: write**, and mandatory Metadata: read. PR labelling accepts Pull requests: write;
   no separate Issues permission, administration, packages or signing scope is requested.
2. Create the existing environment `scarab-release-pr` manually. Restrict deployments to branch
   `main` only, require the independently reviewed User/Team IDs, prevent self-review, and disable
   administrator bypass. Put `SCARAB_RELEASE_PR_APP_PRIVATE_KEY` **only in that protected environment**;
   do not expose it as a repository-level secret. Set `SCARAB_RELEASE_PR_APP_CLIENT_ID` as its environment
   variable. Store/rotate the key securely; revoke it in the App and replace the environment secret
   if compromised. The installation token is short-lived and the pinned action revokes it afterward.
3. Review the explicit reviewer IDs and enable
   [release-please-environment-policy.json](../release-please-environment-policy.json) through a PR.
   It currently has `enabled: false` and no reviewers. The workflow first GET-validates the existing
   environment **without referring to it as a job environment**, then references the returned name,
   waits for approval and rechecks its exact snapshot before requesting the key. Missing or
   unobservable protections fail closed; the workflow does not create/fix settings.
4. Review the bootstrap authority source/reason or authenticate a later stable receipt. Ensure the
   required CI workflows run on the selected target and install the required check policy separately.
5. Run a manual preview. For an actual PR write, select `write: true` and approve the protected job.
   When updating an existing PR, supply its exact reviewed `expected_pr_head`. Ordinary `GITHUB_TOKEN`
   is read-only here and is never an App fallback. App-created PR events should trigger normal CI;
   retain that actual run and head as installation evidence before claiming this is configured.
   GitHub documents the [default token's event suppression](https://docs.github.com/en/actions/how-tos/write-workflows/choose-when-workflows-run/trigger-a-workflow).

No credentials/settings were installed here, so an App-created remote PR/CI-trigger receipt remains
an explicit owner setup obligation. Local library and transport tests are not that receipt.

## Review, concurrency and recovery

There is one branch per approved target, `release-please--branches--<target>`, shared by all channels.
One workflow concurrency group serializes coordinator runs. A new frozen integration source must be
the target tip at write preflight. Open-PR arrivals regenerate from that source and renew review;
source files added after an older selection are not silently deleted.

The PR writer authenticates Git through the App installation token in its process environment,
never a token in a URL/command argument. It uses atomic Git `--force-with-lease` for the exact
expected PR head (or branch absence for first creation). Existing generated-file edits, extra
commits, edited PR title/body, removed pending label or unexpected diff are refused. Review and
reconcile human changes before retrying; the coordinator does not erase them automatically.

Git push and PR metadata updates are separate operations. If push succeeds but the REST PR write
fails, the generated branch remains for review. Available exact proposal/patch bytes and an explicit
`incomplete` result are retained in the workflow artifact; that record never claims PR success. Retrying needs that exact reviewed branch head;
the writer verifies its generated receipt/diff, then may create the missing PR. Do not delete or
force-reset an unexpected branch to recover. Repeating unchanged successful input is a no-op.
A newer target commit requires a newly frozen source and review; final publication must refuse
unaccounted changes at the final selected cut.

## Proposal handoff

The patch contains `.release/proposal.json` and identical `.release/proposals/<version>.json`.
These are **proposed** records, not tag, build or publication authority. They bind the pre-generated
source parent, baseline authority, target, version/channel/date, explicit maintenance/recovery
choices, conventional commit identities, control-plane config hash, full fragment plan and notes.
`fragmentPlanSha256` hashes the existing collector's `json_bytes(plan)`; notes hash literal UTF-8
bytes. `generatedFiles` binds the manifest/notes/stable cleanup; the two identical proposal records
are excluded from their own hash map.

Production notes are `.release/changelog/<version>.md`; prerelease notes are
`.release/notes/<version>.md`. `scripts/release_proposal.py:load_proposal(repo, cut)` returns
`receipt`, literal `notes` bytes and `fragmentPlan` only after regenerating the original plan,
checking same-cut manifest/notes/files, source ancestry and exact final diff. An unrelated change
after preparation requires regeneration. A later trusted coordinator must independently approve
the merged cut, authenticate baseline/publication authority, and bind the exact selected tag,
artifact inputs and receipts. It cannot treat the proposal's parent source as the released cut.

## Evidence and upstream boundary

Tests use the actual pinned library to prove two consecutive stable proposals, a fragment arriving
while one PR is open, idempotency, all four channels, pre/post 1.0 policy, pending/RC recovery,
restoration/version reservation and explicit overrides. Real disposable bare Git repositories prove
lease refusal on concurrent updates. Collector, original-byte pin and same-cut checks have negative
controls. Published-baseline test seams exercise comparison mechanisms; they do not perform or
assert real Scarab publication/signing.

The pinned upstream implementation used here is
[release-please17.11.2](https://github.com/googleapis/release-please/tree/v17.11.2):
[manifest lifecycle](https://github.com/googleapis/release-please/blob/v17.11.2/src/manifest.ts),
[default versioning](https://github.com/googleapis/release-please/blob/v17.11.2/src/versioning-strategies/default.ts),
[public registration exports](https://github.com/googleapis/release-please/blob/v17.11.2/src/index.ts)
and [SCM contract](https://github.com/googleapis/release-please/blob/v17.11.2/src/scm.ts).
The adapter rejects `Release-As` during commit processing because upstream can honor it before
calling the custom versioning strategy. Merged stable pending detection uses the collector's
hash-checked receipt, not release-please's GitHub-label publication inference. Large human notes
stay in the versioned reviewed file; short PR text prevents a competing overflow branch.
