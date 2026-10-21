# Release publication and independent verification

This is an implementation for review, not an activated publisher. The checked-in
[policy](../release/publication-policy.json) is disabled. No environment, App,
registry package, official tag or release is created by installing these files.
[#75](https://github.com/KD5UJC-XYZ/scarab/issues/75) remains open for operator setup
and actual production acceptance. See the [release design](design/releases.md),
[candidate pipeline](release-candidates.md) and [reproducible builds](reproducible-builds.md).

## Authority and prerequisites

The owner must review and install the repository rules described by
[promotion_rules.py](../scripts/promotion_rules.py), then commit the corresponding
publication policy. The release App may bypass **tag creation only**; immutable
tag update/deletion rules have no bypass. It needs Contents write and Actions write
for the explicit exact-tag dispatch, not Administration write. Its private key must
be an environment secret named `SCARAB_RELEASE_APP_PRIVATE_KEY`; its App ID is the
environment variable `SCARAB_RELEASE_APP_ID`. Do not install the private key as a
repository secret. GitHub-token Packages write is used only for staging/version
aliases; OIDC is used for artifact attestations.

Declare all four planned channel policies: `release-alpha`, `release-beta`,
`release-preprod`, and `release-prod`. Provision and protect only the channels being
activated; others may remain uncreated. The selected environment must exist before
its workflow can proceed. Each activated environment must have
independent required reviewers, prevent self-review, observably disable administrator
bypass, and use the exact reviewed custom branch/tag restrictions. Permit trusted
`main` for reservation and the appropriate release tags for each channel. The policy
records reviewer IDs and typed branch/tag patterns. GET preflight must succeed
**before** a job names the environment; otherwise no credential-bearing job starts.
After approval the job repeats the policy and retained audit checks before minting
an App token or generating signatures. Missing API fields fail closed.

Ruleset GET responses may redact bypass actors for minimal Apps. An owner with
settings visibility can retain the full GET responses and render a reviewed audit
using `promotion_rules.py --snapshot FILE --repository KD5UJC-XYZ/scarab --promotion-app-id ID --release-app-id ID`.
Prefer the committed policy snapshot. An explicit dispatch audit is also supported:
preflight bounds it to 64 KiB, validates the full expected policy, retains its original
canonical bytes and SHA256 for the approver, and repeats that check after approval.
The live App must still see the same IDs/update timestamps and its exact bypass
modes. This trusts repository administrators and the API; timestamps are not a
cryptographic defense against an administrator.

The canonical control workflows at the selected cut must match approved current
`main`. Privileged scripts come from that approved control plane. First publication
requires the exact `release/<channel>` tip. A retained attempt can retry after the
branch advances only when its immutable source/tag/candidate identities still agree.
The source and tag object come from the server, not merely checkout metadata.

Release preparation supplies `load_proposal` and pinned Scriv requirements. Its
proposal is planning evidence, not a tag or permission to publish. The coordinator
regenerates same-cut notes and fragment coverage. An initial bootstrap is allowed
only when the enabled owner policy pins the same unpublished planning source/version.
Later `published-stable` baselines require the original independently verified
receipt bytes, their reviewed SHA256/source, and a genuine canonical attestation.

## Coordinator sequence

Dispatch [release-publication.yml](../.github/workflows/release-publication.yml)
on trusted `main` with `operation=reserve`, the approved exact cut, tag, channel
and current control-plane SHA. The protected App reserves a deterministic lightweight
tag and explicitly dispatches the same workflow **at that tag**. It never relies on
an implicitly triggered event from `GITHUB_TOKEN`. Tags are the version authority:
`alpha.N`, `beta.N`, `rc.N`, and unsuffixed versions correspond to alpha, beta,
preprod, and prod.

The state sequence is `draft → draft-staged → verified → published → aliased`:

* The canonical reusable candidate job runs `--check` at the exact cut. A separate
  exact-cut tooling/security job runs Python/inventory/error/crypto controls,
  full-history gitleaks and the repository Semgrep rules. PR-only status contexts
  are not misrepresented as release-cut checks. The publisher checks the actual
  successful jobs and original Actions artifact ID/digest/source/run. Candidate
  `status=verified` alone describes inventory binding, not a full-check result.
* Staging copies the verified OCI manifest by digest to a source/candidate staging
  label and creates a GitHub draft with all release assets and original signature
  bundles. GHCR staging is **not assumed private**. Existing assets or tags with
  different bytes are refused, never replaced. The owner must establish package
  creation/association and visibility permissions. Staged reads can use scoped
  credentials; the package must permit genuinely anonymous pulls before public
  verification can pass. No package visibility setting is changed by this code.
* A signed whole-candidate USTAR archive retains all nested native, legal,
  reproducibility and verifier inputs. The receiver authenticates the archive
  **before** bounded regular-file-only extraction, then checks the expected original
  `candidate.json` hash and runs the complete candidate validator. Standalone JAR
  and BOM downloads are checked separately. The archive must fit GitHub's individual
  asset limit (strictly below 2 GiB); an oversized candidate is an incomplete gate,
  not silently truncated or split.
* Independent staged verification retrieves draft bytes and OCI content, checks
  repository/source/ref/issuer/signatures and subject-bound SBOM predicates, then
  exercises the actual JAR and image. Draft visibility requires a push-authorized
  token; this verifier job has Contents write for that API visibility, remains
  protected by the environment, and its implementation performs only GETs.
* Only an authenticated staged verification receipt can make the draft public.
  Public verification then uses anonymous content downloads and an empty registry/
  Docker authentication configuration. It repeats integrity/signature and runtime
  checks. The actual pulled JRE runs the pinned-JDK-compiled resource/service,
  multi-release, BuildInfo, Bouncy Castle and RocksDB checks as well as portable
  JNI/Plutus and standalone CLI checks. Java never runs on the host.
* The final coordinator authenticates the public receipt, sets the version image
  tag only if absent or already identical, and records completion in release notes.
  No production/channel alias or GitHub latest marker is advanced early. GitHub and
  GHCR are not atomic; a failure remains explicitly incomplete.

All ordinary artifacts/signatures are attached while the release is a draft, which
supports GitHub immutable releases. Public verification finishes afterward. Its
**original receipt bytes** and SHA256 are retained in a bounded base64 notes marker;
its signature remains in GitHub's attestation service. `published_receipt.extract_receipt`
recovers those bytes, but unsigned notes are never authority: the consumer must run
`published_receipt.authenticate` against the canonical workflow/source/tag. A later
reviewed baseline can retain the original bytes in Git. Inventory/licence reports
are not legal or advisory approval, even when no unresolved declaration is counted.

Retries use the original canonical candidate run, artifact ID/digest and manifest
hash through the explicit retained-candidate inputs. They do not rebuild and replace
immutable evidence. A published incomplete attempt can resume public verification;
a completed attempt does not republish assets. Keep the original run/artifact and
receipt evidence until recovery completes. Do not delete/recreate an official tag
or overwrite an asset to repair an incomplete attempt.

## Actual production gate

Production requires a Scarab-produced block accepted by a genuine independent native
peer on preprod. Following or adopting another node's block, successful unit tests,
class-path acceptance and a caller JSON success flag do not satisfy it. The planned
October 23 availability is an operational prerequisite, not a calendar bypass.

[release-production-acceptance.yml](../.github/workflows/release-production-acceptance.yml)
provides a bounded observer for an **already-running** owner-operated forger and
native node. The owner must configure a protected `scarab-preprod-observer` runner
and the policy's explicit forger/native container, host socket, original header
path and bounded log-window start, plus independently reviewed preprod genesis
SHA256 values. No such runner/process/configuration is supplied by this change.

The observer never starts/restarts a node, initiates forging, accesses keys, changes
network state or archives process environments/mounts/logs. It verifies the running
OCI digest/config and embedded JAR, original header hash and public pool/VRF identity,
ordered matching `forgeStarted`/`forged`/`adopted` JSON events, genuine pinned native
process binary and its actual genesis/config/socket mapping, then acquires that
same produced point through native NtC at magic 1. JSON logs must be on stdout and
the bounded window must include `forgeStarted`. Operator-provided log/header history
and the trusted runner/Docker daemon/native process are explicit trust assumptions.
Only public selected fields enter the receipt.

A separate hosted job attests the protected observation. Publication accepts its
explicit original run locator only after checking the canonical exact-source/tag
workflow, successful observer/hosted-attestor jobs, retained artifact and actual
attestation run identity. The proof binds final candidate/JAR/OCI subjects, reviewed
genesis roots and the produced point. Missing real evidence refuses production.
No genuine Scarab produced/adopted preprod observation or publication was executed
while implementing these controls.

## Validation scope

Focused tests use real loopback HTTP transfers and a synthetic, structurally valid
candidate. Archived public preprod headers test original-byte extraction. They are
mechanism controls, not new native forging verdicts. The pinned real `gh` verifier
was also exercised against genuine upstream `cli/cli` signatures with wrong-source,
workflow, repository/ref and tampered-byte negatives; that verifies the selected tool,
not a Scarab publisher identity. Actual GitHub environment/App/OIDC/GHCR publication
and the production observation remain owner activation and #75 acceptance work.
