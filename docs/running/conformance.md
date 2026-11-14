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
   verified against its commit or sha256 and treated as untrusted data: nothing in it is run. A directory kept from
   an earlier run is reused only while its files are byte for byte those it was verified with.
3. It runs the tool in a container of the image with no network, the checkout, the dependency cache and the inputs
   read-only.
4. It checks the report: standard JSON (no `NaN` or infinities), valid against the scenario's `schema.json` at the
   pinned site commit, and, with `implementation_commit` in the schema, naming the checkout's commit. It also
   checks that the report covers the corpus, matching results as the site does: in `ledger-rules` each fixture
   once; in `uplc-conformance` each vector once by id, language and protocol version, none without a verdict; in
   `cbor-decoding` exactly the samples of the era. `uplc-turbo` has no such check, since its tool leaves out a
   program that does not evaluate and the site counts it as failed; the provenance records how many it reports.
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

## Names and the reports repository

A report is `scarab-YYYYMMDD-<scenario>.json.gz`, or `scarab-YYYYMMDD-cbor-decoding-<era>.json.gz`, dated by its
`generated_at` in UTC, with `scarab-YYYYMMDD-<scenario>[-<era>].provenance.json` beside it.

Reports are published to the public repository `KD5UJC-XYZ/scarab-conformance`, which holds nothing else:

- `reports/<scenario>/scarab-YYYYMMDD-<scenario>[-<era>].json.gz`: every published report, with its provenance;
- `latest/<scenario>[-<era>].json.gz`: the newest report of each scenario and era, with its provenance;
- `README.md`, which `publish.py` writes from `latest/`.

The site reads a node's reports from the URLs of its entry, one per scenario and one per era for `cbor-decoding`,
and decompresses a `.gz` it downloads. Scarab's entry names the fixed files of `latest/`, for example
`https://raw.githubusercontent.com/KD5UJC-XYZ/scarab-conformance/main/latest/ledger-rules.json.gz` and
`.../latest/cbor-decoding-conway.json.gz`.

## Publishing on demand

`scripts/conformance/publish.py` commits reports and their provenance to the reports repository and pushes them.
Unless `--apply` is given it is a dry run (`--dry-run`, the default) that prints every action and runs none: no
`gh` or `git` command, and no file written:

```sh
python3 -I scripts/conformance/publish.py <dir> --dry-run  # the default
python3 -I scripts/conformance/publish.py <dir> --apply    # clones the reports repository, commits and pushes
```

With `--apply` it checks that each report's Scarab commit is on GitHub (with `gh`), clones the reports repository
shallowly with your own Git credentials (`--repo-url` names another; `--checkout <dir>` uses an existing clone),
copies each report under `reports/`, points `latest/` at it unless a newer report is there, writes `README.md`,
commits with a message that names the Scarab commits, and pushes to `main`. It refuses a file whose provenance does
not match it or records a failed check or uncommitted changes, a report whose commit is not on GitHub, and a dated
report that is already published, unless `--replace` (a second run on the same day replaces the first).

On GitHub, the **Conformance reports** workflow (`.github/workflows/conformance.yml`, run by hand only) runs the
runner for the scenarios ticked (`ledger-rules`, `uplc-conformance` and `cbor-decoding`; turbo is not offered) and
keeps the files as a workflow artifact. When `publish` is ticked and the run is on `main`, a job of its own checks
out the reports repository with the deploy key in the secret `CONFORMANCE_REPO_DEPLOY_KEY` and runs
`publish.py --apply` there; this repository's token stays read-only. The job fails at once if the secret is not
set. A turbo report from a local machine is published with `publish.py` directly.

### Setting up the reports repository and its key

Done once, by a maintainer of both repositories:

1. Create the public repository `KD5UJC-XYZ/scarab-conformance` with a first commit on `main` (for example
   GitHub's "Add a README"); `publish.py` replaces that README.
2. Make a key pair for it alone, outside any checkout:
   `ssh-keygen -t ed25519 -N '' -C scarab-conformance-deploy -f <key-file>`.
3. Add `<key-file>.pub` to the reports repository as a deploy key with write access (Settings, Deploy keys).
4. Store the private key as an Actions secret of `KD5UJC-XYZ/scarab`:
   `gh secret set CONFORMANCE_REPO_DEPLOY_KEY --repo KD5UJC-XYZ/scarab < <key-file>`, then delete `<key-file>`.

The key can push to the reports repository only. To revoke it, delete the deploy key; to rotate it, repeat
steps 2 to 4.

## Moving to a newer corpus

The site marks a report outdated when its `dataset_commit` is older than the latest change of the corpus it
compares. To follow a newer corpus, change the pins in `scripts/conformance/pins.json` in a commit of their own
(the site commit and the ledger-rules dataset commit move together), run the scenarios and publish.
