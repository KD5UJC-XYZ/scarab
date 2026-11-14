<!-- SPDX-License-Identifier: Apache-2.0 -->
# Conformance reports for node-diversity-conformance

Part of [Running the node and the devnet scenarios](../running.md).

pragma-org's [node-diversity-conformance](https://pragma-org.github.io/node-diversity-conformance/) site compares
the reports that independent Cardano nodes publish for four scenarios: `ledger-rules`, `uplc-conformance`,
`uplc-turbo` and `cbor-decoding` (one report per era, `conway` and `dijkstra`). The site runs nothing itself: it
downloads each node's newest report when it is built. Scarab writes its reports with the `tools` entry points
named in [the code map](../codemap.md), and publishes them on demand, never on a schedule. The Plutus scenarios
report Scarab's own evaluator (`--plutus-vm scarab`).

## Running a scenario locally

`scripts/conformance/run.py` does the whole run for the scenarios it is given:

```sh
python3 -I scripts/conformance/run.py ledger-rules uplc-conformance cbor-decoding --out <dir> [--owner <label>]
```

1. It builds `tools` in the JDK image (`scarab-jdk:local`, built from `docker/jdk.Dockerfile` when absent or with
   `--build-image`) and exports its classpath; nothing runs Java or sbt on the host. In a linked worktree set
   `SCARAB_CACHE_DIR` as for any build ([CONTRIBUTING.md](../../CONTRIBUTING.md#build-docker-only)). A scenario
   whose tool is not in the checkout is skipped before anything is fetched, and says so.
2. It fetches each scenario's inputs, pinned in `scripts/conformance/pins.json`, into a directory of its own under
   `--inputs` (default `<dir>/inputs`): the site at a commit (fixtures, expectations and schemas), Amaru's shared
   ledger-rules documents at the corpus commit, the uplc tarballs and the CBOR dataset at a commit. Each is
   verified against its commit or sha256 and treated as untrusted data: nothing in it is run.
3. It runs the tool in a container of the image with no network, the checkout, the dependency cache and the inputs
   read-only.
4. It checks the report against the scenario's `schema.json` at the pinned site commit, that every fixture or
   vector has one result, and that `implementation_commit`, in a schema that has it, is the checkout's commit.
5. It writes the report, compressed, beside a `.provenance.json` (commit, inputs and pins, tool and arguments,
   image, checks, timings; no host name or path) and `summary.json`.

A report names its commit, so the checkout must have no uncommitted or untracked changes (`--allow-dirty` runs
anyway, and `publish.py` then refuses the report). Each run takes a fresh `--out`: the runner refuses one that
already holds reports, so that `publish.py` takes only that run's. The exit status is 1 if a scenario failed. `--dry-run` prints
the plan without building, fetching or running anything.

`uplc-turbo` measures time, so it runs ad hoc on a quiet local machine with a fast core, one run at a time, and
never on CI (the runner refuses it there):

```sh
python3 -I scripts/conformance/run.py uplc-turbo --out <dir> [--cpus 3] [--memory 8g]
```

A busy or shared machine moves its `cpu_us` measurements and the score the site gives them.

## Names

A report is `scarab-YYYYMMDD-<scenario>.json.gz`, or `scarab-YYYYMMDD-cbor-decoding-<era>.json.gz`, dated by its
`generated_at` in UTC. The date is the only run of eight digits in the name: the site takes, for each scenario, the
newest file of a release whose name carries a date and matches the asset glob of Scarab's node entry
(`scarab-*-<scenario>.json`, the compression suffix aside). The provenance file,
`scarab-YYYYMMDD-<scenario>.provenance.json`, matches no such glob.

## Publishing on demand

`scripts/conformance/publish.py` uploads reports and their provenance to the `conformance-reports` pre-release of
`KD5UJC-XYZ/scarab`, creating it when absent. It is published, never marked latest, and its notes say that it
holds generated reports and is not a Scarab release. Without `--apply` it only prints the `gh` commands:

```sh
python3 -I scripts/conformance/publish.py <dir>            # dry run
python3 -I scripts/conformance/publish.py <dir> --apply    # needs gh with write access to the repository
```

It refuses a file whose provenance does not match it or records a failed check or uncommitted changes, a report
whose commit is not on GitHub, and a file of the same name already released, unless `--replace` (a second run on
the same day replaces the first).

On GitHub, the **Conformance reports** workflow (`.github/workflows/conformance.yml`, run by hand only) runs the
runner for the scenarios ticked (`ledger-rules`, `uplc-conformance` and `cbor-decoding`; turbo is not offered),
keeps the files as a workflow artifact, and publishes them when `publish` is ticked and the run is on `main`.
A turbo report from a local machine is published with `publish.py` directly.

## Moving to a newer corpus

The site marks a report outdated when its `dataset_commit` is older than the latest change of the corpus it
compares. To follow a newer corpus, change the pins in `scripts/conformance/pins.json` in a commit of their own
(the site commit and the ledger-rules dataset commit move together), run the scenarios and publish.
