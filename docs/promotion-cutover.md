# Protected promotion cutover

**Status: Implemented tooling; activation, Apps and protection settings remain owner operations
under [#161](https://github.com/KD5UJC-XYZ/scarab/issues/161). Work still targets `main`.**

The [release design](design/releases.md#branches-and-protected-promotion) requires
`feat/*` -> `dev` -> `main`, with only same-repository `dev` PRs entering `main` after cutover.
Other targets accept any source. The four distribution branches use PRs too.

## Metadata checks and the merge boundary

`pr-policy.yml` validates conventional PR titles and, when `SCARAB_PROMOTION_POLICY` is
`dev-only-main`, the source repository and branch of PRs to `main`. An absent variable means
`transition`; unknown values fail. It reads GitHub's event JSON using trusted default-branch
code, with a read-only token, no PR checkout, secrets, dependencies or build commands.

A required status check is bound to a commit, not to one PR. A valid `dev` PR and a forbidden
feature PR can share a commit and its green check. The metadata check therefore provides feedback;
the main update restriction and the live merge controller enforce the source rule.

After cutover, an owner requests **Promote dev to main** from `main`, supplies the reviewed PR
number and full head/base commits, then obtains approval in the existing `promotion-main`
environment. The controller rechecks the live PR and active rulesets, requires same-repository
`dev` -> `main`, and calls GitHub's merge API with the approved head SHA. GitHub still enforces
required reviews/checks; the controller cannot bypass them. A moved head/base, draft, closed PR,
wrong source, unapproved workflow ref, changed policy or incomplete checks refuses the merge.

This replaces ordinary button merges into `main`; it does not restrict sources for other PR
targets. Direct main pushes remain prohibited. The dedicated promotion App can bypass only the
separate main update restriction, and only through a PR. It cannot bypass the reviewed-branch
ruleset or alter immutable release tags. Merge queues are not configured by this tooling.

Keep `dev` current after each promotion: open a reviewed `main` -> `dev` backmerge PR before the
next promotion. A merge commit advances `main` beyond the old `dev` tip; strict up-to-date checks
will otherwise block the next promotion. Preserve that history through PRs rather than resetting
either branch.

## Reviewable settings

Render the four API request bodies without making any remote change:

```sh
python3 scripts/promotion_rules.py \
  --promotion-app-id "$PROMOTION_APP_ID" --release-app-id "$RELEASE_APP_ID" \
  > /tmp/scarab-cutover-rules.json
```

The IDs are real, distinct GitHub App IDs, not installation IDs or the GitHub Actions App ID.
The bodies specify:

| Ruleset | Subjects | Bypass |
|---|---|---|
| Scarab reviewed branches | `main`, `dev`, all four `release/*` branches | None; reviewed PR, latest-push approval, resolved threads, required checks, no force push/deletion |
| Scarab main promotion | Updates of `main` | Dedicated promotion App, PR only |
| Scarab authorized release tags | Creation of `v*` tags | Dedicated release coordinator App |
| Scarab immutable release tags | Updates/deletions of `v*` tags | None, including the release App |

Creation authority is separate from immutability. Giving the release App a bypass on a combined
creation/update/deletion ruleset would allow it to rewrite release identities.

The required checks are the named always-reporting jobs in `promotion_rules.py`. PR CI, native
comparison and decoder fuzz reporting cover every protected target; main push checks stay enabled.
CDDL selection reports **CDDL comparison gate** on every protected PR, and builds its tool only
when relevant inputs change. A selection failure or requested comparison failure fails that gate.
Path-filtered workflows must not be selected as required checks that can remain pending forever.

## Owner activation sequence

1. Merge release PR preparation, candidate, publication and promotion tooling PRs and verify their
   CI. The `release proposal controls` required job is supplied by release PR preparation; install
   that workflow before requiring its check. Review the rendered rulesets, the independent-review
   requirement and the replacement of main's merge button. An actual independent reviewer is
   required; automation does not make an owner's self-approval independent.
2. Reconcile `dev` with `main` through reviewed PRs. Create the release branches only when needed;
   this is not authorization for another forced alignment or branch-history reset.
3. Register a dedicated promotion App, installed only on Scarab, with contents write and
   pull requests read. The job requests only those permissions, and its token is revoked on exit.
   It needs no administration, packages, signing or tag-update bypass.
4. Create `promotion-main` before any job refers to it. Restrict it to branch `main`, configure
   explicit reviewers, prevent self-review and disable admin bypass. Store
   `SCARAB_PROMOTION_APP_PRIVATE_KEY` **only as that environment's secret**, never as a repository
   secret accessible to workflows on other branches. Set repository variables
   `SCARAB_PROMOTION_APP_ID` and `SCARAB_RELEASE_APP_ID` to their reviewed IDs.
5. In a reviewed PR, populate the real reviewer IDs in `release/promotion-policy.json` and set
   `enabled` to `true`. The GET-only preflight requires the actual environment, exact reviewers
   and main-only ref restriction. A missing field or environment fails before a job names it;
   the approved job rechecks the same snapshot before minting a token.
6. Confirm all required check names have reported recently on the protected targets. Apply each
   reviewed ruleset through repository settings or the documented GitHub rulesets API, retaining
   the returned IDs and settings snapshots. Do not grant the Apps a review/status bypass.
7. Using the owner's existing ruleset visibility, GET the four repository rulesets with complete
   `bypass_actors`, `id`, `updated_at`, `source_type` and `source` fields. Save those four responses
   as a JSON array, then render the audit without making a remote change:

   ```sh
   python3 scripts/promotion_rules.py --promotion-app-id "$PROMOTION_APP_ID" \
     --release-app-id "$RELEASE_APP_ID" --repository KD5UJC-XYZ/scarab \
     --snapshot /tmp/scarab-live-rulesets.json > /tmp/scarab-ruleset-audit.json
   ```

   Review the output into `rulesetAudit` in `release/promotion-policy.json` and the publication
   policy through a PR when possible. For initial cutover or recovery after a settings edit,
   supply that complete JSON as the manual workflow's `ruleset_audit` input. The preflight
   validates it against the expected policy, retains its exact canonical bytes and displays its
   SHA-256 before requesting environment approval. The independent reviewer must inspect that
   artifact and digest before approving; the merge job consumes that exact artifact ID. This
   approved input avoids a deadlock where changed settings block the PR refreshing their audit.
   Minimal runtime Apps are not granted administration: GitHub may redact
   other actors from their GET responses. The controller then requires the reviewed settings'
   exact server IDs and update timestamps, every visible rule field, and the requesting App's
   actual `current_user_can_bypass` value. Missing evidence or a changed version refuses the run.
   If actors are visible, they must still match. A supplied snapshot is operator evidence accepted
   through protected approval, not an automated proof of hidden actors. Re-review it after any
   ruleset edit; an unsigned success flag cannot replace the live settings/authority checks.
8. Set `SCARAB_PROMOTION_POLICY=dev-only-main`. Exercise a valid dev promotion and a refused fork
   `dev`, feature and same-SHA feature PR. Inspect the main push checks and retained promotion
   receipt. Verify tag creation authority separately from immutable update/deletion refusal.

Until these settings and actual platform controls have been exercised, report the cutover as
incomplete. Unit/HTTP fixture tests establish controller behavior, not installed GitHub policy.
The GitHub owner can change settings; ruleset/deployment history is the audit trail for that trust.
The reviewed snapshot and server update timestamp detect ordinary settings drift, not a malicious
repository administrator or changes hidden within the timestamp's resolution. Owner administration
remains a trust boundary. See the [ruleset GET visibility contract](https://docs.github.com/en/rest/repos/rules#get-a-repository-ruleset).

GitHub currently documents a default public-repository `pull_request_target` policy moving to
enforcement on November 2, 2026. Review the repository's Actions event policy before enabling
the required metadata check; explicitly allow this reviewed metadata-only workflow if necessary.
Do not enable unsafe PR checkout. See [GitHub's security guidance](https://docs.github.com/en/actions/reference/security/securely-using-pull_request_target).

## Credential rotation and recovery

Rotate the promotion App's environment secret through GitHub's App key management and revoke the
old key after a successful controlled run. Revoke the installation/key to stop promotion.
Recovery changes to rulesets, reviewer identities or bypasses require explicit owner review and
retained settings evidence; do not weaken a failed preflight or silently reuse a release tag.

GitHub's references describe [commit-bound required checks](https://docs.github.com/en/pull-requests/how-tos/merge-and-close-pull-requests/troubleshooting-required-status-checks),
[per-ruleset bypasses and restrictions](https://docs.github.com/en/repositories/configuring-branches-and-merges-in-your-repository/managing-rulesets/available-rules-for-rulesets)
and the [rulesets API](https://docs.github.com/en/rest/repos/rules).
