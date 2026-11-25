<!-- SPDX-License-Identifier: Apache-2.0 -->
# Conformance reports for node-diversity-conformance

Part of [Running the node and the devnet scenarios](../running.md).

pragma-org's [node-diversity-conformance](https://pragma-org.github.io/node-diversity-conformance/) site compares
the reports that independent Cardano nodes publish for four scenarios: `ledger-rules`, `uplc-conformance`,
`uplc-turbo` and `cbor-decoding` (one report per era, `conway` and `dijkstra`). The site runs nothing itself: it
downloads each node's newest report when it is built. Scarab writes its reports with the `tools` entry points
named in [the code map](../codemap.md). The CBOR detector and `uplc-turbo` run nightly; publication stays on demand.
The Plutus scenarios report Scarab's Plutus evaluator, its own (`uplc`).

## Running a scenario locally

`scripts/conformance/run.py` does the whole run for the scenarios it is given:

```sh
python3 -I -B scripts/conformance/run.py ledger-rules uplc-conformance cbor-decoding --out <dir> [--owner <label>]
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
   image, the runner, the CPU a tool container sees and the container's limits, checks, timings; no host name or
   path) and `summary.json`.

The workflow also runs `scripts/conformance/summarize.py --out <dir>`. Its `cbor-outcomes.md` is the job summary,
and, for the uplc-turbo job, `turbo-score.md` (below); `cbor-outcomes.json` retains matched and mismatched counts by
era and rule, source identities and every mismatched sample's name. A successfully generated report may contain disagreements: the summary says **MISMATCHES**, emits
a workflow warning and keeps those results visible. It does not add a merge gate or prevent publishing an honest
report. Missing or invalid report evidence is **INCOMPLETE**, never a conformance pass.
With `--previous <earlier-run-dir>`, the summarizer identifies new and resolved disagreements only when dataset,
protocol version and sample identities agree. Without comparable evidence that number is unknown, not zero.

The corpus and the node's reference build can name different upstream implementations even when the era and
protocol version agree. The report uses the node's production decoders; it does not substitute another decoder
to make a corpus sample pass. Triage a mismatch against both pinned implementations before changing admission.
Check the actual native instance too: a corpus can use a test-only `DecCBOR` instance where the node uses
`DecCBOR (Annotator ...)`, with different admission rules. A narrower standalone type also needs its own checks:
the Dijkstra corpus's `auxiliary_scripts` is a sequence of `Timelock`, whereas full Dijkstra auxiliary data can
carry the guard constructor of `DijkstraNativeScript`.
A valid sample also needs a re-encoding: missing encoders and missing harness embeddings are conformance gaps,
not evidence that the production decoder refused the sample. The `language` rule uses the bounded `Language`
reader, not the open `Word8` key of a Conway cost-model map. Shared Dijkstra encoders consume decoded fields;
`DijkstraCodecs` rebuilds Dijkstra certificates, proposals, parameter updates, scripts, datums, relays and outputs
from their decoded values. Output re-encoding chooses the reference's list or map form; it does not replay the
whole output's input bytes. Standalone DReps and pool metadata are decoded inside minimal certificates.
Standalone Dijkstra block bodies are decoded inside a minimal Leios block; the selected production decoder
judges their layout. A body it accepts still reports a missing encoder until production retains and writes
that body's complete original encoding.
Byte-exact rules consume the original bytes retained by their production decoder. These wire re-encodings do
not change persisted-state codecs or the reference profile selected by the node.

## Nightly CBOR detector

The existing workflow runs `cbor-decoding` nightly, with both complete pinned era corpora and all normalisation
vectors, and `uplc-turbo` in a job of its own (below). It does not publish reports automatically or add a required
PR check. The job's summary gives the
conformance verdict; a successful report-generation job alone is not a conformance pass. Reports, provenance,
outcome summaries and diagnostic logs have bounded workflow-artifact retention. The workflow reads the most recent
successful nightly run's artifact for comparison. A first run, expired/missing artifact or incompatible corpus
gives an unknown comparison; local comparisons can use two retained run directories with `summarize.py --previous`.

The detector's historical positive control is [#550](https://github.com/KD5UJC-XYZ/scarab/issues/550), fixed by
[#628](https://github.com/KD5UJC-XYZ/scarab/pull/628): an anchor URL above 128 UTF-8 bytes was accepted. The pinned
dataset `ed6c49a4e273bb540a26c46d61c011974d0192f6` contains
`dataset/conway/url/invalid-zap-1/00001-ccbbf62b010f35dd.input.cbor`, a valid definite text string of 129 UTF-8
bytes (SHA-256 `ccbbf62b010f35dde2d12314f79e2318a9f7693aabf67a4e7e6b384c818eea91`). It is embedded as an
anchor's URL, with a 32-byte hash, exactly as `ConwayRules` reaches the production `Anchor.decode`.

On 2026-11-15, immutable development-source exports of affected revision
`be6309ab7fc5591c27835d008069f5c2b3cd9c70` and fixed revision
`1b84d9944017fddfae3bb56621480c74698b565c` were built in the same Docker JDK image
`sha256:b958d3cb2430d6130e46c104cbfb371731e36569a13c2c580e79ae8dbf5d1924` (Java 25.0.4.1, sbt 1.10.7,
Scala 3.3.8). A test entry point read that original file, wrapped it as `[url, bytes32]`, called `Cbor.decode`,
then the revision's own `Anchor.decode`. The only API adaptation was the fixed reader's explicit Conway decoder
version. The affected revision returned `accepted`; the fixed revision returned
`rejected: TextTooLong(anchor url,128,129)`. Both compiled and ran successfully. This demonstrates detection of
one historical defect, not complete conformance. Commands and the compact probe are retained in
[#1082](https://github.com/KD5UJC-XYZ/scarab/issues/1082); raw build outputs are not published.

A report names its commit, so the checkout must have no uncommitted or untracked changes (`--allow-dirty` runs
anyway, and `publish.py` then refuses the report). Each run takes a fresh `--out`: the runner refuses one that
already holds reports, so that `publish.py` takes only that run's. The exit status is 1 if a scenario failed. `--dry-run` prints
the plan without building, fetching or running anything.

`uplc-turbo` measures each program's CPU time and the bytes it allocates, at the runner's own settings (3 CPUs,
8 GB, a 4 GB heap), on one reference CPU: `TURBO_REFERENCE_CPU` in `scripts/conformance/scenarios.py`, the model of
the GitHub-hosted runners it is written on, as `/proc/cpuinfo` names it. Runners cannot be chosen by CPU and the
score moves with the CPU much more than from run to run
([section 68 of the performance log](../performance/rewards-and-evaluation.md#68-uplc-turbo-on-github-hosted-runners-the-score-by-cpu-1411)),
so on GitHub Actions `run.py` probes the CPU in the image before it builds the tools and, on another CPU, or one the
probe cannot name, ends the scenario there as `not-reference-cpu`: no report, no failure, and the job summary says
so; re-run the job to be given another runner. `--any-cpu` (the workflow's `uplc-turbo-any-cpu`) measures on any CPU
instead, and the nightly run does so, for a trend with the CPU beside it. `publish.py` refuses a `uplc-turbo` report
measured on another CPU, or on one its provenance does not name, wherever it was written: a report from a local
machine is published only if that machine has the reference CPU, so publication relies on no machine of ours. When
GitHub retires the hardware, change the reference in a commit of its own, with a measurement in the performance log.

```sh
python3 -I -B scripts/conformance/run.py uplc-turbo --out <dir>
```

The restricting budget is 10 billion CPU units and 14 million memory units through protocol 10,
then 16.5 million memory units from protocol 11, per the [clarified scenario](https://github.com/pragma-org/node-diversity-conformance/blob/628006278309c02a1c3e49dce00e43595d925c2e/scenarios/uplc-turbo/README.md).
These are Plutus execution units. The runner measures JVM allocation separately in bytes.

Timings still vary from run to run on the reference CPU, and with them the score the site gives a program near its
CPU limit. The provenance records, in `environment.cpu`, what a tool container saw: the CPU's `model` (none when the
kernel names none, as on most ARM CPUs), the machine's `logical_cpus` (`/proc/cpuinfo`), `available_cpus`, which is
what `nproc` reports in the container (the image's `nproc` counts the container's quota), `quota_cpus`, the quota
itself as the container's cgroup states it (`cpu.max`; none when it sets no limit), and `failure`, why the probe gave
no CPU count, when it gave none; `environment.limits` holds the container's `--cpus` and `--memory`.
`scripts/conformance/provenance.py` gives the record's whole shape, which `run.py` checks before it writes a record
and `publish.py` before it stages one.

The bytes a program allocates depend mostly on the JVM's settings and CPU count, which the runner fixes, and little
on the machine's speed or load: a program's figure moves by a fraction of a per cent from run to run, since the
tool averages the thirty fastest of fifty evaluations. For a run asked for `uplc-turbo`,
`summarize.py --inputs <inputs>` writes `turbo-score.md` and `turbo-score.json`: the programs met by the site's rule
(`score.py`), those missed on memory (whatever their CPU time), on CPU alone, not reported or with nothing to
compare, each missed program with its CPU time and memory as multiples of its budget (the page lists the 50 furthest
over budget in the dimensions that fail them), the programs within the memory limit, the median and 95th percentile
of bytes per memory unit, the CPU, and whether the report is publishable. It scores a report only once it and its
provenance hold together and the site under `--inputs` is the one its receipt verified (the checks of `publish.py`,
below); otherwise the page says **INCOMPLETE**. On another CPU it says **NOT MEASURED**.

## Names and the reports repository

A report is `scarab-YYYYMMDD-<scenario>.json.gz`, or `scarab-YYYYMMDD-cbor-decoding-<era>.json.gz`, dated by its
`generated_at` in UTC, with `scarab-YYYYMMDD-<scenario>[-<era>].provenance.json` beside it.

Reports are published to the public repository `KD5UJC-XYZ/scarab-conformance`, which holds nothing else:

- `reports/<scenario>/scarab-YYYYMMDD-<scenario>[-<era>].json.gz`: every published report, with its provenance;
- `latest/<scenario>[-<era>].json.gz`: the newest report of each scenario and era, with its provenance;
- `README.md`, which `publish.py` writes from `latest/`: a Results section with the met, total and ratio of each
  report and the overall score, as the site computes them, and the provenance of each report.

Pragma reads Scarab’s five fixed report URLs from its
[Scarab registration](https://github.com/pragma-org/node-diversity-conformance/blob/628006278309c02a1c3e49dce00e43595d925c2e/nodes/scarab/metadata.json).
Publishing a report updates the corresponding file under `latest/`.

## Publishing on demand

`scripts/conformance/publish.py` commits reports and their provenance to the reports repository and pushes them.
Unless `--apply` is given it is a dry run (`--dry-run`, the default) that prints every action and runs none: no
`gh` or `git` command, and no file written:

```sh
python3 -I -B scripts/conformance/publish.py <dir> --dry-run  # the default
python3 -I -B scripts/conformance/publish.py <dir> --apply    # clones the reports repository, commits and pushes
```

With `--apply` it checks that each report's Scarab commit is on GitHub (with `gh`), fetches the site at its pinned
commit as the runner does (into `--inputs`, reusing a copy verified there, else into a temporary directory of its
own) and checks each report against it, clones the reports repository shallowly into that temporary directory with
your Git credentials (`--repo-url` names another), writes each report under `reports/`, points `latest/` at it
unless a newer report is there, writes `README.md`, stages exactly those files, commits with a message that names
the Scarab commits, and pushes to `main`.

It reads each file once, and the bytes it checks are the bytes it writes. A report and its provenance must hold
together (`scripts/conformance/evidence.py`): the provenance has the shape `run.py` writes and names the file, its
size and sha256; the file is gzipped standard JSON whose sha256 the provenance names; the report and its provenance
agree on the scenario, the era, the time it was generated (which dates its name), the Scarab commit where the
report names one, and the CPU of a timing report (`machine.cpu`); and the report is valid against the scenario's
schema at the pinned site and readable by the site's scoring, which refuses, for instance, an `experimental` report.
A dry run checks all this but the site part, and that too when `--inputs` holds a verified site. These checks give
consistency, not authenticity: they show that a report is the one its provenance describes and that the site will
take it, not that a measurement was made as its provenance says. A file that fails one refuses the whole
publication. For each run directory given, a scenario its `summary.json` names without a report, such as
uplc-turbo off the reference CPU, is printed as not published, and its `latest/` stays as it is.

The README's Results are computed again at each publication from the reports `latest/` then holds, by
`scripts/conformance/score.py`, which mirrors the site's scoring at the pinned site commit and cites it rule by
rule: each scenario weighs the same in the overall score, a scenario without a report the site can read counts
zero, and `cbor-decoding` is scored by its Conway report, Dijkstra being shown but not scored while Conway can be
read. The site's data that scoring needs (the ledger-rules fixtures, the uplc-conformance expectations, the
uplc-turbo corpus) comes from the pinned checkout, so moving the site's pin means reading its scoring again at the
new commit: the publisher refuses a pin that is not the commit `score.py` mirrors. One rule of the site is left
out: it also fails a uplc-turbo benchmark whose budget disagrees with the other nodes' reports, which the reports
repository does not hold. The clone is
removed on normal exit and on exceptions: a failure before the push publishes nothing, so run it again once
the cause is fixed. Beyond the checks above, it refuses a report whose provenance records a failed check or
uncommitted changes, a timing report of another CPU than the reference, a report whose commit is not on GitHub, and a
dated report that is already published, unless `--replace` (a second run on the same day replaces the first).

On GitHub, the **Conformance reports** workflow (`.github/workflows/conformance.yml`, run by hand, and nightly for
the CBOR detector and `uplc-turbo`) runs the runner for the scenarios ticked (`ledger-rules`, `uplc-conformance`,
`uplc-turbo` and `cbor-decoding`) and keeps the files as workflow artifacts. `uplc-turbo` runs in a job of its own,
on a runner of its own, whose CPU is the one that matters, and keeps an artifact of its own
(`conformance-turbo-<run>`); the other scenarios' job and artifact (`conformance-reports-<run>`) hold nothing of it.
The turbo job may fail without failing the run (`continue-on-error`): the failure is red on that job and in its
summary, and the run stays the CBOR baseline of the next nightly run, which takes the newest successful scheduled
run's report. Dispatched runs share one concurrency group and run one at a time: while one runs, the next dispatch
waits, and a later one replaces it (GitHub cancels the waiting run), so dispatch another only once the one before it
has started; the nightly run has a group of its own.
When `publish` is ticked and the run is on `main`, a job of its own runs
`publish.py --apply` on what both jobs kept, with Git reaching the reports repository over SSH through the deploy
key in the secret `CONFORMANCE_REPO_DEPLOY_KEY` (`GIT_SSH_COMMAND`, checking GitHub's published host keys); this
repository's token stays read-only. The job fails at once if the secret is not set, or if `uplc-turbo-any-cpu` is
ticked: such a run is never published. Since `publish.py` takes a `uplc-turbo` report of the reference CPU alone, a
run cannot publish a turbo score of another CPU: when the turbo job was given another CPU, or failed, the other
scenarios are published, `latest/uplc-turbo` stays as it is, and the publish job's summary says why in a
`not published: uplc-turbo` line. A failed reports job publishes nothing. A report written locally is published
with `publish.py` directly.

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
