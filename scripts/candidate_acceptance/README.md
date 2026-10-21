<!-- SPDX-License-Identifier: Apache-2.0 -->
# Offline candidate acceptance

Status: **all 12 mandatory external cases and all 66 additional controls passed**, with zero
skips, missing cases, failures or errors, on 2026-10-21 UTC. The
[retained acceptance receipt](../../docs/data/external-acceptance-294.json) records the measured
source, actual Docker image, test JVM observations, complete input inventory identity and report
hashes. The default input manifest remains deliberately unavailable: operators supply the reviewed
manifest and original private datasets explicitly. See [#294](https://github.com/KD5UJC-XYZ/scarab/issues/294).

## Preserved inputs and completed preparation

Archive `scarab-acceptance-inputs-2026-10-20`, supplemented by its `gate-2026-10-21` input/evidence
set, retains original native observations, all 1,451 maintained V5 snapshots, authentic captures,
signed manifests and worker reconstruction instructions. Access mappings remain in the private
worker handoff. Original expected results were not regenerated. The input tree uses hard links
and read-only mounts rather than a second bulk copy; its complete per-file inventory is verified
before and after execution.

The untouched original Mithril bootstrap was reconstructed offline through production import using
its original artifact/certificates/digests and official pinned client. It restores slot 125660129,
block 4769524, with 3,192,290 UTxOs. Native Haskell indexes and recorded Scarab boundary data have
separate input roles; the reviewed native source contains 29,091 complete triplets. Maintained
preprod identity metadata was upgraded once to V6 with retained original bytes and conversion
provenance; acceptance does not perform legacy adoption.

The run compares all 4,526,058 preprod table entries and matching checkpoint keys/semantics,
original native state/table export bytes and V5 import/restore, six unchanged boundary digests,
5,683 captured preprod blocks across serial/parallel modes, and 8,380 original Preview blocks.
Preview compares every required native field at the one original indexed reference point and
reproduces all nine expected invalid transactions, with no collection/unavailability failures.
This is bounded native projection and Scala execution evidence, not universal whole-state parity.

Confirmed harness failures were corrected rather than waived: storage's ordinary in-process
sbt grouping ignored the requested fork heap; default subprocess working directories hid maintained
fixtures; export needed a writable sibling lock outside its read-only database; and signed import
needed the ancillary manifest's immutable tail triplet. Raw failing reports and positive probes are
retained alongside the successful receipt. No production ledger or codec logic changed.

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
the controller; its sibling `state` and `meta` must accompany it. The reviewed manifest pins the
genuine complete native secondary-index count; native Haskell indexes and the current-format
Scarab snapshot corpus occupy separate roles. The Plutus baseline retains its original fixed
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

The controller explicitly overrides `Test / testGrouping` with a subprocess group. Storage's
ordinary build group uses `Tests.InProcess`, which ignores `Test / fork := true` and its heap
options. Gate-only runtime observations in the native-index and boundary tests assert and record
at least a 16 GiB maximum heap and exactly four configured processors. The final receipt requires
both actual observations; merely mentioning JVM options in a command cannot establish the runtime.
The ordinary small-heap storage regression remains part of normal `check`, outside these selected
external suites. A reproduced old-policy run fails the heap assertion before parsing native files.

Forks also pin their working directory to the build root. The native ledger-file and streaming
suites load maintained fixtures relative to that root; module-default fork directories cannot
supply them. Runtime guards record and assert `/work` alongside heap and processor observations,
and receipt validation rejects a module-directory observation. This leaves all fixture reads and
byte/state comparisons intact.

The export database has an additional read-only bind below owned writable results. Its stable
ownership lock is a sibling of the database, so mounting the entire parent read-only prevents
export before any comparison. Only that scratch parent is writable; original database bytes stay
read-only and the export suite still checks their complete before/after fingerprints. The signed
preprod ancillary manifest's complete file list, including its native immutable tail triplet,
must accompany the ledger files; the recorded Scarab database is a separate input role.
Maintained recorded databases use the current network-identity format. Fixture preparation
upgrades their metadata once through the normal admission API, retaining the original identity
and conversion provenance separately. Acceptance does not adopt legacy identities while running.
The upgraded metadata records operator confirmation rather than inventing historical proof of
the genesis fields omitted by the original identity. Native signed ancillary bytes stay unchanged.
