<!-- SPDX-License-Identifier: Apache-2.0 -->
# Offline candidate acceptance

Status: controller implemented; **real external datasets are unavailable and acceptance has not
run**. The tracked input manifest deliberately has `status: unavailable`, null URL/hash/provenance
and therefore fails before a download, Docker build or test invocation. This is not an acceptance
receipt. Work is tracked by [#294](https://github.com/KD5UJC-XYZ/scarab/issues/294).

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
the controller; its sibling `state` and `meta` must accompany it. K1 includes all 29,031 secondary
indexes and its current-format snapshot corpus; the Plutus baseline retains its original fixed
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
