# Releases and repository hardening

**Status: Owner decisions accepted (2026-10-21); implementation plan, not an enabled release service.**
Tracks [#161](https://github.com/KD5UJC-XYZ/scarab/issues/161),
[#75](https://github.com/KD5UJC-XYZ/scarab/issues/75) and
[#76](https://github.com/KD5UJC-XYZ/scarab/issues/76).

## Decisions and current boundary

Scarab uses plain SemVer, with protected Git tags as the release authority. Cardano compatibility
is an evidence-backed matrix, not encoded in Scarab's major version. GitHub Actions is the
authorized publisher: runnable binary artifacts in GitHub Releases, and OCI images in GHCR linked
from those releases by immutable digest. A runnable JVM fat JAR is a binary deliverable requiring
Java 21; this plan does not promise a standalone native executable or untested platforms.

The adopted preprod block remains a v0.2 exit gate. Per the owner's 2026-10-21 scheduling update,
production cannot happen before October 23; that date is not evidence the gate passed. Planning,
release tooling and hardening preparation proceed while the gate stays open. Do not publish a
v0.2 production release or claim milestone completion before its acceptance evidence exists.
Repository protection/settings and credential installation are maintainer operations; none are
applied by this document. Runtime, build and publication implementation is staged below.

## Branches and protected promotion

**Transition:** work targets `main` for now (owner direction, 2026-10-21). The owner authorized
aligning `dev` to that source after landing this plan; preserve its old history and use an explicit
lease before the one-time forced update. This is not standing authorization for future resets.
The table below describes the policy after the owner activates the cutover, not an already
required source check on current PRs.

After cutover, normal development is `feat/*` -> `dev` -> `main`. `release/beta` and `release/prod` are distribution
branches, also protected by pull requests. Beta/prod denote release channels, not a claim that
Scarab is safe on a particular Cardano network.

| PR target | Permitted PR source | Intended use |
|---|---|---|
| `main` | Only `dev` from this same repository | Reviewed promotion of integrated work |
| `dev` | Any branch | Normal feature integration and reconciliation |
| `release/beta` | Any branch; normally `dev` | Reviewed beta preparation |
| `release/prod` | Any branch; normally `main` | Reviewed production preparation |
| Other branches | Any branch | No additional source-branch restriction |

Require checks and PRs for protected integration/release branches; independent review for
consensus, cryptography, persistence and key handling follows #161. A required metadata-only
check must enforce both the `dev` head name and source repository for PRs to `main`; branch
protection alone does not express this source rule. Never execute untrusted PR code with publisher
credentials. Required checks must actually run for each protected target, including changes to
PR base/head and merge-queue use if enabled. The owner controls bypasses and their audit trail.

For later divergence, reconcile histories through reviewed PRs into `dev`; do not reset branches
without separate owner authorization. A branch rename does not perform integration.
The old `main` hotfix-line and `release/mainnet`/`release/dev` proposals are superseded. Urgent fixes
also reach `main` through `dev`; the other targets retain the source freedom stated above.

## Version authority and proposal

[SemVer](https://semver.org/) defines version meaning. Conventional commits propose increments;
a reviewed release PR selects the version; an immutable protected tag identifies the released
source. These roles are different. Release tags are `vMAJOR.MINOR.PATCH` and beta tags are
`vMAJOR.MINOR.PATCH-beta.N`, for example `v0.2.0` and `v0.2.0-beta.1`.

Use Ottochain's release-please plus sbt-dynver arrangement as the reference. Release-please's
manifest is planning state, not another published-version authority. Do not add a separately
hand-maintained `VERSION` as an independent truth. Configure the build to derive the version
from the exact selected tag and refuse release builds with a dirty tree, absent/moved/ambiguous
tag, incorrect channel, unapproved source, or mismatched metadata. Remove the existing hard-coded
build version only when this implementation and its controls land. Development versions must
be recognizably unreleased and record the commit; release jobs cannot use a fallback version.

The immutable-source reproducibility wrapper exports source without `.git`. Resolve and verify
tag, commit and version before export, then freeze that metadata with the source inputs for both
clean builds. Both JARs, BuildInfo, SBOMs, provenance and image labels must agree. Do not let dynver's
missing-Git/date fallback change the exported version or mistake a nearby beta tag for a stable
release. Beta and stable version strings differ: prove each build separately instead of asserting
that a beta JAR is byte-identical to a stable JAR with a different embedded version.

Conventional `feat`, `fix` and breaking-change markers feed the next-version proposal. The initial
implementation should explicitly configure and test the reference's pre-1.0 policy: breaking
changes advance the minor, compatible features advance the minor, fixes advance the patch.
After 1.0, incompatible changes advance the major. Review compatibility and migration impacts;
commit classification is not proof that an interface is compatible. A maintenance-only release
can be explicitly proposed and reviewed even without a feature/fix commit.

## Changelog fragments and consolidation

The existing [fragment contract](../../changes/README.md) remains the human-written changelog
source. The [local preparation tool](../changelog-preparation.md) implements immutable selection,
pinned Scriv collection, preview, checked stable cleanup and pending-attempt recovery. Publication
and version/tag authority remain separate implementation stages. Conventional commit subjects choose
a proposed bump; they must not replace or duplicate the fragment prose. The current `CHANGELOG.md` also contains legacy Unreleased text: inventory it and
include it exactly once in the first applicable release, not just the directory's newer fragments.

This is the established news-fragment pattern implemented by
[Scriv](https://scriv.readthedocs.io/en/latest/commands.html) and
[Towncrier](https://towncrier.readthedocs.io/en/stable/tutorial.html). Scriv is the first tool to
trial because it collects Markdown fragments, supports keeping or removing consumed fragments,
and can preserve uncategorized prose. Towncrier is an alternative with typed fragment filenames.
Neither is assumed to accept Scarab's topic-bearing headings and mixed legacy fragments without
verification. Stage 1 must prove lossless handling of the actual corpus before selecting/pinning
a tool or adding a narrow adapter; do not rewrite the accumulated explanations wholesale.

The release PR is the consolidation review, with a reproducible algorithm:

1. Freeze the candidate revision, explicit proposed version/date and previous successfully published and verified stable baseline.
   Inventory eligible tracked fragments by path and SHA-256. Exclude the directory README,
   templates and already consumed identities. Do not collect files from an arbitrary live checkout.
2. Validate structure and fail with file/line for malformed fragments. Review legacy exceptions
   into valid entries before enabling the guard. Preserve headings, issue references and prose.
   Order numeric issue filenames numerically, then remaining paths lexically; retain section
   order within each file. Multiple fragments for one issue are legitimate. Never deduplicate
   distinct entries merely because they share an issue or similar wording.
3. Render one version section and the same human-readable release notes. Require one copy of
   every selected entry, preserve historical release sections and include the first-release legacy
   Unreleased text. Record selected paths/hashes, baseline and output hash in release metadata.
   A reviewer may improve wording in the source fragments and regenerate; do not silently shorten
   entries or let release-please/another workflow generate a competing changelog.
4. In that same preparation PR, delete only selected, hash-matching fragments after their text is
   incorporated into the stable changelog. Abort on changed/missing selected input or unexpected
   output; leave newly arrived and deferred fragments untouched. No broad `rm changes/*.md`.
5. Regeneration with identical inputs must produce identical bytes. CI verifies the proposed diff
   and receipt; a repeated application is a no-op or an explicit already-consumed result, never a
   duplicate version section. Parallel arrivals require an updated selection and renewed review.

Beta notes are a versioned snapshot, initially cumulative since the last stable release; beta
preparation retains the fragments. Stable consolidation consumes them in a reviewed preparation
PR into `dev`, then `dev` promotes to `main`, then the selected source promotes to `release/prod`.
This keeps the stable changelog and cleanup in the integration history without a forbidden
release-branch PR to `main`. Preserve separately published beta note snapshots. If a release
branch receives additional user-visible fixes, reconcile their fragments through `dev` before
stable consolidation, and require exact final candidate coverage before publication. A failed
publication retries the same tagged release and notes; Git history retains the consumed inputs.
Allow only one pending stable consolidation. Until it publishes successfully, beta collection
must also recover its consumed-but-unpublished entries using the retained receipt's source
revision and path/hash identities, unioned with new pending fragments by identity. Reading only
the surviving `changes/` files would omit unreleased work. An abandoned stable attempt requires
a reviewed rollback/restoration through `dev` before another stable consolidation; never reuse
or move its reserved tag. Preserve published sections and previously published beta snapshots.
A receipt remains available after success to prove selection and prevent duplicate consumption.

Release-please manages the version proposal and release PR lifecycle; fragment tooling owns
changelog rendering. Configure release-please for PR preparation only
(`skip-github-release: true`); one trusted coordinator owns tag creation, draft staging, final
publication and channel aliases. Do not copy Ottochain's public-release-first/deploy-notification
path. Their integration must be tested as one coordinated PR update path, including two
consecutive release proposals and new fragments arriving while a release PR is open. Stock commit-based changelog replacement and
Ottochain's separate commit-log overwrite are not the Scarab implementation.

## Publisher credentials and distribution

Separate three capabilities rather than create one broad release secret:

- Release-PR/tag automation: prefer a narrowly scoped GitHub App installation token. Ottochain's
  `RELEASE_TOKEN` PAT is a reference mechanism, not mandatory for Scarab. If a PAT is selected,
  scope, expiry, rotation and owner are documented. A human approval can also be the trigger.
- Asset and package publication: use job-scoped `GITHUB_TOKEN` permissions (`contents: write` for
  release assets and `packages: write` for repository-linked GHCR publication), restricted to
  trusted release jobs. Ordinary PR checks retain read-only permissions and no publisher secrets.
- Signing/provenance: use GitHub OIDC and artifact attestations, with `id-token: write` and
  `attestations: write` limited to the attesting job. Bind the authorized repository, workflow,
  ref/source and subject digest. A long-lived personal signing key is not required by this design.

GitHub documents [workflow event/token behavior](https://docs.github.com/en/actions/how-tos/write-workflows/choose-when-workflows-run/trigger-a-workflow).
Do not assume a release created with `GITHUB_TOKEN` automatically triggers a separate
`release: published` workflow. Use an App event or explicit/reusable orchestration and test it.
Pin actions by commit and tool/build images by immutable identity. Define protected environments,
release/tag authority, rotation/revocation and least-privilege owner setup before enabling writes.

Publish the runnable fat JAR and checksums to GitHub Releases. Link the
[GHCR](https://docs.github.com/en/packages/working-with-a-github-packages-registry/working-with-the-container-registry)
image using its full digest and record architecture/platform support. Mutable `beta`/`latest`
aliases are conveniences, never evidence identities; beta cannot update the production alias.
Release notes include compatibility, migration/operator notes, downloads, image digest/pull command,
SBOMs, provenance/verification instructions and the independent verification receipt.

Coordinate publication: build and verify first, upload candidate assets to a draft release, stage
the GHCR image by digest, then independently retrieve and verify both before marking the release
complete and advancing aliases. GitHub Releases and GHCR are not an atomic transaction; retain
an explicit incomplete/failed state, retry immutable inputs, and never advertise partial success.
The post-publication verifier also checks the publicly downloadable files after publication.
A manual retry accepts an existing validated tag, not an arbitrary branch/version override.

## Acceptance under issue 75

| Obligation | Required evidence |
|---|---|
| SBOMs | CycloneDX inventory of resolved transitive JVM dependencies reconciled with the assembly, bundled JNI resources/platforms and licences; separate image/OS inventory. Schema/coverage checks bind each BOM to its artifact. Advisory/licence review is separate from inventory generation. |
| Signing | Verifiable signed attestations over final JAR, SBOM and OCI subjects; verification checks the approved GitHub publisher/workflow identity and source/ref. Checksums alone are not a signature. |
| Provenance | Exact tag/commit, source export, pinned builder/JDK/toolchain, resolved dependency identities, parameters, native inputs, artifact hashes and two-clean-build receipt. Native source rebuild/byte comparison is [#229](https://github.com/KD5UJC-XYZ/scarab/issues/229). |
| Published verification | A separate job downloads release assets and pulls the GHCR digest; checks identity/source/subjects, BOM binding and hashes, compares the downloaded JAR with its reproducibility receipt, runs standalone/native/resource smoke checks and retains results. Wrong identity, modified bytes and mismatched BOM must fail. |

Fat-JAR byte reproducibility is implemented in the
[immutable-source verifier](../reproducible-builds.md), with bounded evidence in
[#276](https://github.com/KD5UJC-XYZ/scarab/pull/276); it does not establish authenticated downloads,
source-built JNI or OCI image byte reproducibility. Reuse that implementation for these stages. Start with the evidenced Linux/amd64 native profile; add each platform
only with its own build/runtime evidence. Define image reproducibility separately. Use
[GitHub artifact attestations](https://docs.github.com/en/actions/how-tos/secure-your-work/use-artifact-attestations/use-artifact-attestations)
without claiming a SLSA level merely because an attestation exists.

## Implementation sequence and closure

1. **#161: promotion, version and fragments.** Reconcile integration branches; implement source-rule
   and conventional-title checks; trial and pin the fragment collector on the actual corpus;
   implement release-please integration, tag-derived sbt/export metadata and compatibility-row
   validation. Prove concurrent fragment arrival, malformed input, repeat collection, beta retention,
   stable cleanup, stalled/abandoned publication, version mismatch and forbidden-main-source
   controls. Owner applies rules/settings.
2. **#75 / #229: candidate artifacts.** Reuse the clean fat-JAR verifier; rebuild native inputs;
   create candidate JAR/image inventories and SBOMs, immutable input/provenance records and smoke
   receipts. No publisher credentials in PR builds. Check tooling compatibility with pinned sbt.
3. **#75: authorized publication.** Install approved minimal credentials/environment policies;
   implement signing/attestation, draft/staged publication, independent retrieval/verification and
   failure/retry controls, including rejection of public completion/production-alias advancement
   before both artifact verifications pass. Dry-run orchestration before enabling beta publication;
   production still requires the v0.2 gate and appropriate release approval.
4. **#76: operator delivery.** Document release downloads and GHCR use. Compose/Kubernetes,
   persistent volumes, metrics/health checks and role/key-mount conventions remain its separate
   packaging acceptance, not implied by publishing an image.

Implementations update this status and their issue checklists with exact source/run/artifact
receipts. Planning does not close #75/#161/#76 or change milestone/protection settings. Release
support lives in [the release compatibility section](../compatibility.md#release-support), supported
by the component/evidence matrix rather than inferred from a SemVer number.

## Reference inspected

Ottochain at `7ada01f1b8433b50acd5d86aa2388dd3f0e611f2`:
[release-please workflow](https://github.com/scasplte2/ottochain/blob/7ada01f1b8433b50acd5d86aa2388dd3f0e611f2/.github/workflows/release-please.yml),
[JAR release workflow](https://github.com/scasplte2/ottochain/blob/7ada01f1b8433b50acd5d86aa2388dd3f0e611f2/.github/workflows/release.yml),
[container workflow](https://github.com/scasplte2/ottochain/blob/7ada01f1b8433b50acd5d86aa2388dd3f0e611f2/.github/workflows/docker.yml),
[release configuration](https://github.com/scasplte2/ottochain/blob/7ada01f1b8433b50acd5d86aa2388dd3f0e611f2/release-please-config.json)
and [dynver settings](https://github.com/scasplte2/ottochain/blob/7ada01f1b8433b50acd5d86aa2388dd3f0e611f2/version.sbt).
This is the reference for release-please/tag/dynver and dual distribution. Scarab adds fragment
consolidation, Docker-only reproducible builds, protected promotion and the #75 assurance gates.
