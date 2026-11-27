<!-- SPDX-License-Identifier: Apache-2.0 -->
# Timing of the secret paths

Part of [Running the node and the devnet scenarios](../running.md).

The steps of VRF proving and KES signing that read a secret are written in the constant-time arithmetic of the
vendored curve code, and the guard of [CRY-1](../design-rules.md#cry-1) keeps every variable-time routine out of
their packages. That is the structure of the source. Whether their time depends on the secret once the JVM runs
them is a matter of measurement, and this check is that measurement: `SecretPathTiming`
([source](../../tools/src/main/scala/xyz/kd5ujc/scarab/crypto/vrf/SecretPathTiming.scala)), run by hand, or nightly
and on demand by [secret-timing.yml](../../.github/workflows/secret-timing.yml) through
[scripts/secret_timing.py](../../scripts/secret_timing.py). This page is the home of what the check covers, how it
decides and what to do with its result; the first measurements and the history of the harness are in
[performance section 67](../performance/mainnet-follower.md#67-timing-of-the-vrf-secret-path-fixed-against-random-secrets-1418).

**Its limit.** The JVM does not guarantee constant time. The JIT compiles each method from the profile of its calls
and may compile a branch-free select into a branch, or a mask into a jump, and may compile the same bytecode
differently on another JVM, with other flags or after another history of inputs; the processor may take a time that
depends on an operand. Nothing in the source can rule that out, and this measurement is the only evidence either
way. It is evidence of one kind: a difference it does not find at its size on its machine, not a proof that there is
none.

## What it measures

The method is dudect's (Reparaz, Balasch and Verbauwhede, "Dude, is my code constant time?", DATE 2017). For each
operation, two classes of its secret, one fixed and one random, are measured interleaved in a random order, with
every input chosen before the timing starts and the two classes' inputs laid out in memory alike; Welch's t of the
two classes' times is computed on all measurements and on each of dudect's 100 crops (the measurements below the
percentile 1 − 0.5^(10(i + 1)/100)). A target's statistic is the larger of |t| on all measurements and the largest
|t| over the crops. Above 10 is dudect's "probably not constant time" (its `t_threshold_moderate`), above 500
"definitely not" (`t_threshold_bananas`). Two differences from dudect's own program: it leaves a crop out of its
largest |t| until the crop holds 10,000 measurements of a class, where this tool takes every crop, and it adds a
second-order test, which this tool does not run.

| Target (`id`) | Operation | The secret: fixed class against random | Measurements by default |
|---|---|---|---|
| `vrf-prove` | `Draft03Key.prove`: Gamma = [x]H, the nonce, U = [k]B and V = [k]H, the challenge and s = c x + k | the key's seed, zero against random seeds; the same H for both classes at each measurement | 25,000 |
| `vrf-output` | `Draft03Key.output`: Gamma, and the hash of [8]Gamma | as `vrf-prove` | 25,000 |
| `kes-sign` | `Sum6KesSigning` signing at period 0: the leaf's Ed25519 signature (SHA-512 of its seed, [x]B, the nonce, [r]B, the challenge, S) and the stored verification keys | the KES seed, zero against random seeds; the same 256-byte message for both classes at each measurement | 25,000 |
| `kes-evolve` | an evolution from period 1 to period 2, which erases a half and generates the other from its seed: two Blake2b-256 expansions and two leaves' public keys | as `kes-sign`; each measurement moves a copy of a key at period 1, made untimed just before | 25,000 |
| `kh-zero`, `kb-zero` | `EdwardsPoint.multiplyRadix16` ([k]H) and the basepoint table's (`[k]B`) | the scalar, zero against random scalars | 100,000 |
| `kh-once`, `kb-once` | the same two multiplications | a scalar drawn at random once against random scalars | 100,000 |

Three controls run beside them, and decide whether the run says anything at all:

| Control (`id`) | What it is | The run is inconclusive when |
|---|---|---|
| `control-random` | [k]H with both classes drawn from one pool of random scalars: nothing to find, whatever the multiplication does (two pools would differ by their draw, and a leak in the multiplication would turn that into a reading) | it is above 10: the machine reports a difference where there is none |
| `control-field` | Bouncy Castle's field arithmetic in place, an element drawn once against random ones: no table, no selection, no branch or index on its data | it is above 10: a value repeated against changing ones differs on this machine by itself, so a secret target's difference of that size cannot be told from it |
| `control-vartime` | the variable-time double-and-add of `Ed25519Point.scalarMultiply`, a scalar of one bit against random ones: a leak by construction | it is not above 10: the run could not see a leak that is there |

### What it does not cover

- **Other secret steps of the node.** The reading of the key files (their text envelopes and hex, `secret.KeyText`),
  a VRF key's public key computed when it is read, and the checks of a KES key when it is read (which generate
  subtrees from their seeds and compare verification keys) run at start and at each reload, and are not timed.
  Neither is the comparison of a slot's VRF output with the leader threshold, nor KES key generation. An
  evolution's work depends only on the period, which is public: the one timed runs the operations every evolution
  runs, and the move into the right half of the top level runs them for 32 leaves instead of 2.
- **Other Ed25519 signing.** The node signs nothing else with Ed25519: an operational certificate is signed with
  the cold key by `cardano-cli`, outside the node. Signature and proof verification take public inputs only, and
  are variable-time by design ([CRY-1](../design-rules.md#cry-1)).
- **Small leaks.** A difference smaller than what the runner's own noise hides at this many measurements is not
  seen. A leak of the size of one secret-dependent branch in a multiplication of tens of microseconds may be below
  it.
- **Other channels.** One call's time on the clock is all it sees: not the cache lines or branch-predictor state
  that another process on the same core could observe, nor power, nor a frequency that moves with the data (the
  Hertzbleed class, which the field-arithmetic control may show as a value effect).
- **The node's own compilation.** The tool calls the code the node calls, but from its own call sites and with its
  own JVM flags (the serial collector, compilation in the foreground); the node's JVM compiles the same bytecode
  from the node's profile.

## The nightly check

[secret-timing.yml](../../.github/workflows/secret-timing.yml) runs every night and on demand (Actions tab: its
inputs are the runs and the measurements), on a GitHub-hosted `ubuntu-24.04` runner. `scripts/secret_timing.py`
builds the JDK image, probes the CPU a container sees, builds `tools`, and runs the test three times, one after
another, each in a fresh container (no network, the checkout read-only) and a JVM of its own (`-XX:+UseSerialGC
-Xbatch` and a fixed heap). The job is stopped after 90 minutes, a run after 25. Its summary has every target's
statistic in every run, the runner's CPU and the verdict; the reports (`run-N.json`), the outputs (`run-N.log`),
the summary and the provenance (commit, image, JDK, CPU, limits) are kept as the workflow artifact
`secret-timing-<run id>-<attempt>` for 30 days and published nowhere.

The rule:

1. A run is **conclusive** when its three controls read as above.
2. A secret target is **above** in a run when its statistic is above 10, the tool's threshold, dudect's.
3. A **leak is confirmed** when one secret target is above in two conclusive runs. This is the rule of Goodwill,
   Jun, Jaffe and Rohatgi ("A testing methodology for side-channel resistance validation", NIST NIAT 2011, the
   methodology known as TVLA): a device is rejected only when Welch's t exceeds the threshold in two independent
   experiments, so that an excess by chance, unlikely to repeat, does not fail it. They leave the threshold to each
   test; here it is dudect's 10. They also ask for the same sign in both experiments; this rule does not, since the
   targets whose fixed secret is drawn once draw it anew in each run, and a leak may then be slower in one run and
   faster in the other.
4. Otherwise, with fewer than two conclusive runs the check could not have confirmed a leak, and it is
   **inconclusive**; with two or more, **no leak was found**, and a target above in one run only is named in the
   summary.

| Exit status of `secret_timing.py` | Verdict | The job |
|---|---|---|
| 0 | no leak found | green |
| 1 | leak confirmed | red, with an error annotation |
| 2 | inconclusive (the devnet scenarios' convention) | green, with a warning annotation: neither a pass nor a failure |
| 3 | the check could not run (build, tool or report failed, or the arguments are wrong) | red |

Why one run is not enough, and why 10 and not less. With the classes interleaved at random, |t| of two classes of
one distribution is close to a standard normal, and above 10 by chance essentially never, even as the largest of a
target's 101 correlated statistics. What makes a fixed-against-random |t| large without a leak is systematic: the
harness (a fixed input that lies in memory differently from the random ones, which is why the pools are made
alike and staged into one buffer) and the effect of a repeated value on the processor, which the field-arithmetic
control measures. A busy runner adds noise that cropping removes only in part. The control turns the second into
an inconclusive run rather than a failure, and the confirmation keeps a single disturbed run from failing the job.
How often each happens on GitHub's runners is what the nightly summaries show.

**The CPU.** Each result records the CPU model its runner had (as [conformance](conformance.md) records it for
`uplc-turbo`), and the summary says whether it is the reference CPU of the timing scenarios (`TURBO_REFERENCE_CPU`
in `scripts/conformance/scenarios.py`). The verdict does not require that CPU, unlike the `uplc-turbo` score: a run
compares its two classes, and its controls, on one machine in one JVM, and decides nothing from a number measured
elsewhere. That is an argument from the design. A statistic's size does move with the CPU, so the nightly summaries
are read per model, and a model whose controls fail night after night, or whose secret targets are above 10 with no
change to the code, is the evidence that would restrict the check to some models. The artifacts of several runs can
be decided together, each with its own CPU, with `--reports` below.

## What a result means, and what to do

- **Leak confirmed.** One secret target, the same one, was above 10 in two runs whose controls passed. Read the
  summary: which target, how far above, and how near 10 `control-field` read in those runs. Find what changed since
  the last night that was not red: the vendored curve code, the `secret` packages, `Sum6Key`, `Leaf` or
  `Draft03Key`, the JDK image (a new JDK compiles differently), or `SecretPathTiming` itself. Dispatch the workflow
  again (with five runs), and run it by hand on an idle machine. A leak that repeats there is a finding about a
  secret: report it as [SECURITY.md](../../SECURITY.md#reporting) says, and fix the code, not the check; the
  threshold, the rule and the sizes are not changed to make a run pass.
- **Inconclusive.** Fewer than two runs had controls that passed; the summary names the control that failed in each.
  It is not evidence either way. `control-random` above 10: the runner was disturbed; dispatch again.
  `control-field` above 10: this CPU shows a value effect at this size; dispatch again, and if it persists for one
  CPU model, record it in an issue, since on that model the check is blind to a leak of that size.
  `control-vartime` not above 10: the run could not see a gross leak, so something in the runner or the JVM changed
  how the test measures; that is a defect of the check to investigate. Inconclusive night after night means the
  check is not running in effect: file an issue.
- **No leak found.** No secret target above 10 in two conclusive runs: a bounded negative observation for this size,
  this runner and this JVM. A target above in one run is named; watch whether the same one comes back on later
  nights.
- **The check could not run.** The build, a run or a report failed; the step's log and `build.log` and `run-N.log`
  in the artifact say where. Fix it like any red nightly.

## Running it by hand

On an idle machine, from the checkout, with Docker (the program never runs java or sbt on the host):

```sh
python3 -I -B scripts/secret_timing.py --out "$(mktemp -d)" --build-image
```

`--repetitions` and `--measurements` change the size, `--classpath-file` takes a tools classpath already exported
instead of building, and `--dry-run` prints the commands. To decide across the artifacts of several workflow runs,
unpack each into a directory of its own and give their reports together:

```sh
python3 -I -B scripts/secret_timing.py --out "$(mktemp -d)" --reports run-a/run-*.json run-b/run-*.json
```

The tool alone, in the JDK image, prints its table and the run's own assessment, and with a path writes the report:
`java -XX:+UseSerialGC -Xbatch -cp <tools classpath> xyz.kd5ujc.scarab.crypto.vrf.SecretPathTiming [measurements
[report]]` (the export of the classpath is in performance section 67).

**Showing that it can fail.** After a change to the check, put a secret-dependent step into a multiplication the
targets use, run the program, see the targets that take that multiplication confirmed as leaks and the others and
the controls not, then restore the source and check that its diff is empty. Two such changes were tried when the
check was built, in a container on a developer's machine (the pull request has the numbers): a return at once from
`EdwardsPoint.multiplyRadix16` and the basepoint table's `multiplyRadix16` when the scalar's top digit is even,
which every secret target confirmed; and a shortcut that returns the identity for a zero digit from the
variable-base table's `select`, a change one might make for speed, which proving, the output and the two `[k]H`
targets confirmed, and the targets that do not take that table did not.
