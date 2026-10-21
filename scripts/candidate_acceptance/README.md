<!-- SPDX-License-Identifier: Apache-2.0 -->
# Offline candidate acceptance

Status: controller implemented; original inputs have been located and preserved in shared offline
storage, but **a complete controller artifact is not provisioned and acceptance has not run**.
The tracked input manifest deliberately has `status: unavailable`, null URL/hash/provenance and
therefore fails before a download, Docker build or test invocation. That status describes the
provisioning contract, not the absence of every original dataset. Work is tracked by
[#294](https://github.com/KD5UJC-XYZ/scarab/issues/294).

## Preserved inputs and remaining preparation

The 2026-10-21 UTC preservation run, against main
`2c26058cf191535c78f78e6ea9f211d9697c43d9`, verified 545 direct input files and all 1,451
maintained V5 K1 snapshots plus their conversion manifest in operator-managed shared storage.
Archive identity: `scarab-acceptance-inputs-2026-10-20`. Its worker README, `input-files.json`,
`case-input-status.json`, `preservation-receipt.json` and checksum manifests retain the mappings,
original source/conversion identities and verification results; access details remain in the
private worker handoff. Original native observations were not regenerated.

The snapshot archive is 18,284,302,254 bytes, SHA-256
`4b4eb755dbd0178c2a986dcdc5eee78024eeb08dcb638dbd5a1f1898539de3d3`.
All archived file hashes match the originals. The complete new shared input footprint is about
20 GiB; 66 required existing chain chunks were hash-checked and reused. No bulk local staging
copy, operational database change or cloud download was needed. Compression used Zstandard
1.5.7 and GNU tar 1.35. This preservation archive is not the controller's reviewed gzip/USTAR
artifact, and its convenience symlinks are not admissible controller members.

Located inputs include the pinned 4,526,058-output preprod table, its original state/metadata and
signed ancillary manifest, matching V5 snapshot/checkpoint, recorded preprod blocks, complete
mainnet genesis files, original Preview ledger data and Mithril verification material. A Preview
window has its matching V5 snapshot/checkpoint, 8,380 original blocks and an unchanged native
reference body at slot 1123210. Its window-specific index selects an original row; the complete
original index is retained separately. These identities establish availability, not a passing
comparison on the current candidate.

Remaining work under #294:

1. Reconstruct and verify the original untouched Mithril bootstrap. The earlier bootstrap
   database was subsequently replayed and contains later snapshots/checkpoints; it cannot stand
   in for the test's sole original state at slot 125660129. Original native data, archives,
   certificate/digest material and verification provenance survive. Check any reused immutable
   prefix against the artifact digests; the older K1 archive is not automatically that artifact.
2. Separate native Haskell and Scarab K1 input roles. Both tests currently use `SCARAB_K1_DB`,
   but `HaskellRealFilesSuite` reads native primary/secondary indexes while `BoundaryDigestSuite`
   reads Scarab chunks, `.index` files and `format`. Reused chunk bytes match; the archived Scarab
   indexes are v2 while the recorded boundary fixture uses v1. The matching 59 index/format files
   were preserved separately (3,035,630 bytes), without replacing archived indexes.
3. Replace the controller's stale fixed native inventory assumption with a reviewed pinned
   identity/count contract: the archived native source has 29,035 secondary indexes, while the
   current controller requires 29,031. Do not drop original files or mix formats to satisfy it.
4. Provision the complete reviewed artifact, or implement and verify a provenance-preserving LAN
   input mode, then execute all mandatory cases and retain their exact-source/runtime receipt.

**External acceptance remains 0/12 executed under this combined gate.** Earlier historical runs,
ordinary CI and successful input preservation do not close that acceptance requirement.

## Boundary

The normal project check can pass while external-data tests are skipped. The manual
[`candidate-acceptance.yml`](../../.github/workflows/candidate-acceptance.yml) job provisions a reviewed
immutable offline archive and requires **all twelve** identities in [`cases.json`](cases.json) to
pass. Five `FormatBenchSuite` benchmarks have a separate performance contract and are not selected.
The selected eleven complete suites also contain 66 explicitly named ordinary controls; all of
those must pass too. A missing, duplicated, unexpected, failed, errored or skipped case fails the
job. Unknown report files/cases cannot contribute successes.

Whole suites are deliberate: a local real sbt/MUnit probe confirmed that `--tests` filtering records
unselected tests as JUnit `skipped`, rather than omitting them. The gate does not exempt those skips.
It runs the complete declared suites and records exact mandatory/additional counts separately.
The additional identities come from actual JUnit reports and current static Config test names;
when a legitimate unit test is added, review the declaration with its code change.

The ordinary regression controls for the controller use synthetic archives and XML. They prove
safe admission and receipt accounting, **not** execution against a native dataset. The twelve
cases have different evidence contracts: for example, serial/parallel Scala agreement is not
native parity, and an exported projection target is not a completed native comparison.

## Reviewed inputs and resource requirements

[`inputs.json`](inputs.json) is the explicit provisioning contract. Before running, a maintainer
must review and commit the complete artifact identity and provenance: credential-free HTTPS URL,
SHA-256, compressed-byte limit, expanded-byte limit, entry limit and all approved relative paths.
Keep the original native bytes and expectations unchanged. The controller never generates expected
outputs, downloads an unpinned latest dataset, accepts a native non-result as false, or changes a
coverage-register status. The known preprod tables hash is additionally checked independently in
the controller; its sibling `state` and `meta` must accompany it. The current controller requires
29,031 native secondary indexes and a current-format snapshot corpus in one K1 role; the inventory
and format-role corrections above must precede real provisioning. The Plutus baseline retains its original fixed
snapshot/checkpoint stem. Native tests enforce their additional state, keyset and original-byte
contracts.

Package a gzip-compressed USTAR archive with canonical relative regular files and directories only,
materializing any hard links first (`tar --format=ustar --hard-dereference`). Extended PAX/GNU
metadata headers are refused before processing their payloads.
Archive paths must stay beneath declared input roots; traversal, absolute paths, links, devices,
sparse files, duplicate members, signing-key files and resource overruns are refused. Extraction is
outside the checkout and read-only. Docker mounts it read-only as `/candidate/inputs`. Environment
variables are limited to the declared input roles; import databases, projection exports, replay
outputs and other working data are placed in fresh `/candidate/results` scratch directories.
Host environment output overrides and benchmark flags are not forwarded.

The job is manual-only on an explicitly provisioned `self-hosted, linux, x64, scarab-candidate`
runner. It needs Docker, enough disk for twice the reviewed maximum expanded archive size, and at
least 24 GiB available to the container. Boundary-state fixtures need about a 12 GiB heap; the
controller explicitly forks test JVMs with `-Xmx16g` instead of pretending `SBT_OPTS` overrides the
launcher's fixed 2 GiB heap. Storage and node tests run sequentially in one owned container.
The JDK Dockerfile's pinned base and the actual built image ID identify its runtime. No public node,
operational database or signing operation is used.

The mainnet Config test accepts only the explicit `SCARAB_MAINNET_GENESIS_DIR` directory seam;
absence remains optional in ordinary unit tests and is mandatory here. Preview native comparison
requires `SCARAB_PREVIEW_REFERENCE`, an `index.jsonl` and each original co-located
`<slot:012d>-<hash>.json` native reduced body. Its index must contain only original targets in
that particular replay window `(anchor, last]`; a wider epoch index is refused, not filtered. Retain
the original bodies unchanged when preparing a window-specific index. The generated Preview summary must report
`referenceBodyComparisons == referenceTargetsRequired > 0`; `referenceTargetsExported` alone is
insufficient. Native reference fields and comparison implementation remain owned by the replay
contract, not by this provisioning controller.

## Execution, evidence and promotion

After identities are reviewed, run from a clean candidate checkout:

```sh
python3 scripts/check_test_coverage.py
python3 scripts/candidate_acceptance.py --output /tmp/scarab-candidate-unique-run
```

The first command checks ordinary fixture inventory. Do **not** invoke its `--candidate` promotion
policy before provisioning: unresolved evidence remains open until a real successful receipt is
reviewed. This avoids requiring a prior successful job in order to run the job.

Before sbt, the controller removes prior node/storage JUnit reports. On success, `receipt.json`
records the actual source commit, input/case-manifest and artifact hashes, immutable runtime image ID, input inventory,
explicit environment mapping, every JUnit report hash, exact case counts and native comparison
summary hash/counts. Failure writes `failure.json` and never a passing receipt. Available JUnit XML is copied before verifying results, including when sbt fails. The workflow
retains available receipts, reports and logs, then removes only its owned large input/replay data.
The container has a unique name and is removed on success, failure or handled interruption.

This controller depends on the first #294 audit track's final MemPack names/native-index contract
and the replay track's complete native-body comparison/summary. Rebase onto those reviewed changes
before treating a real run as candidate acceptance. Updating coverage status and promoting a
candidate are separate reviewed actions linked to that actual retained receipt; an absent dataset
or successful controller unit test does not close the gap.

## Local inventory mode

For a reviewed LAN/offline tree, use an artifact with `kind: local-inventory`, `url: null`,
`inventory: <relative JSON filename>` and `sha256` equal to that inventory's SHA-256. The inventory
lives beside the input manifest and contains `schemaVersion: 1` and a `files` array of canonical
relative `path`, exact `bytes` and `sha256` fields for every file in the input tree. Bounds apply
both to the inventory document and the complete payload/entry count. `nativeK1SecondaryCount`
pins the native source's genuine complete secondary-index count; it is not a selection limit.

```sh
python3 scripts/candidate_acceptance.py --manifest /reviewed/inputs.json \
  --local-inputs /reviewed/input-tree --output /tmp/scarab-candidate-unique-run
```

All files must belong to declared input mappings. Missing, changed, extra, duplicate, linked,
nonregular or signing-key inputs are refused. The controller verifies the complete tree before
and after execution, mounts it read-only, and never chmods, extracts over or removes these inputs.
`--cleanup` removes only the controller's generated output. The receipt records the inventory
identity as the local artifact identity, distinct from an HTTPS archive identity. Shared dependency
caches are mounted at the same `/work/.cache` path as other worktree builds; the sbt socket stays
private. The default manifest remains unavailable until a real reviewed manifest is supplied.

`SCARAB_K1_DB` supplies the V5 snapshots and matching recorded Scarab immutable data for boundary
checks. `SCARAB_NATIVE_K1_DB` separately supplies the complete original Haskell database for native
index parsing. Fixture trees may be materialized with hard links to immutable original files;
never chmod those shared inodes or allow a writable container mount. Preserving the original
Mithril bootstrap requires keeping its initial snapshot/checkpoint before any follower replay.
