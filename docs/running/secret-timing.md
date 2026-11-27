<!-- SPDX-License-Identifier: Apache-2.0 -->
# Timing of the secret paths

Part of [Running the node and the devnet scenarios](../running.md).

The steps of VRF proving and KES signing that read a secret are written in the constant-time arithmetic of the
vendored curve code, and the guard of [CRY-1](../design-rules.md#cry-1) keeps the names of the variable-time routines
it lists out of their packages. The guard reads names: a call through a helper it does not list is outside it. That is
the structure of the source. Whether their time depends on the secret once the JVM runs them is a matter of
measurement, and this check is that measurement: `SecretPathTiming`
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
every input chosen before the timing starts: each class draws from a pool of 1,024 entries of its own (the fixed
pool holds distinct copies of the fixed value), the i-th measurement takes entry i mod 1,024 of its class's pool, the
two pools are made in one loop, the class whose entry is made first alternating, so that they lie in memory alike, and
an array input is copied into one buffer that every measurement of the target passes. A key is used where it lies, and
when the fixed class's key was always the first of its pair, keys of one and the same seed in both classes read as a
difference; the alternation removes that, and the same-seed control below watches for it. Each result is consumed
whole after the second clock read.

The statistic. A target first times a calibration batch of a tenth of its measurements, in the same random order,
takes the crops' cutoffs from it (dudect's percentiles, 1 − 0.5^(10(i + 1)/100) for crop i of 100) and discards it,
as dudect discards its first batch. Welch's t of the two classes is then computed on all the other measurements and
on each crop, the measurements below its cutoff; a crop counts only when both classes hold at least 1,000
measurements below it. Two classes with no spread at all give 0 when their means are equal and an infinite t when
they differ. A target's statistic is the larger of |t| on all measurements and the largest |t| over the crops that
count. Above 10 is dudect's "probably not constant time" (its `t_threshold_moderate`), above 500 "definitely not"
(`t_threshold_bananas`). The largest of up to 101 statistics is a heuristic: no significance level is corrected for
taking the largest.

Differences from dudect's own program: dudect counts a crop only from 10,000 measurements of a class, which would
leave a target of 25,000 measurements its crops above the 80th percentile only, where 1,000 keeps them from about the
13th; it adds a second-order test, which this tool does not run; and it drops the first ten measurements of every
batch, where this tool warms each target with up to 5,000 calls of each class first.

| Target (`id`) | Operation | The secret: fixed class against random | Measurements by default |
|---|---|---|---|
| `vrf-prove` | `Draft03Key.prove`: Gamma = [x]H, the nonce, U = [k]B and V = [k]H, the challenge and s = c x + k | the key's seed, zero against random seeds; the same H for both classes at each measurement | 25,000 |
| `vrf-output` | `Draft03Key.output`: Gamma, and the hash of [8]Gamma | as `vrf-prove` | 25,000 |
| `kes-sign` | `Sum6KesSigning` signing at period 0: the leaf's Ed25519 signature (SHA-512 of its seed, [x]B, the nonce, [r]B, the challenge, S) and the stored verification keys | the KES seed, zero against random seeds; the same 256-byte message for both classes at each measurement | 25,000 |
| `kes-evolve-0-1` | an evolution from period 0 to period 1, the depth-0 case: the leaf's seed erased and the next one copied into its place, with no hashing and no curve arithmetic | as `kes-sign`; each measurement moves a copy of a key at period 0, made untimed just before | 25,000 |
| `kes-evolve-1-2` | an evolution from period 1 to period 2: a half erased and the other generated from its stored seed, two Blake2b-256 expansions and two leaves' public keys | as `kes-evolve-0-1`, from a key at period 1 | 25,000 |
| `kh-zero`, `kb-zero` | `EdwardsPoint.multiplyRadix16` ([k]H) and the basepoint table's (`[k]B`) | the scalar, zero against random scalars | 100,000 |
| `kh-once`, `kb-once` | the same two multiplications | a scalar drawn at random once against random scalars | 100,000 |

Four controls run beside them:

| Control (`id`) | What it is | It fails when |
|---|---|---|
| `control-random` | [k]H with one pool of random scalars, the i-th measurement taking the same entry whichever its class: nothing to find, whatever the multiplication does | it is above 10: the machine reports a difference where there is none |
| `control-keys` | KES signing with keys of one seed in both classes, two pools made as the targets' are: nothing to find but what the harness makes of keys used where they lie | it is above 10: the harness reports a difference where there is none |
| `control-field` | Bouncy Castle's field arithmetic in place, an element drawn once against random ones: no table, no selection, no branch or index on its data | it is above 10: a value repeated against changing ones differs on this machine by itself |
| `control-vartime` | the variable-time double-and-add of `Ed25519Point.scalarMultiply`, a scalar of one bit against random ones: a leak by construction | it is not above 10: the run could not see a leak that is there |

A diagnostic, never a verdict: for each class, the fastest measurement of every pool entry measured at least five
times, and the entry whose fastest is the slowest; it is printed, and flagged in the report, when it is at least
twice the median entry's fastest.

### What it does not cover

- **A delay confined to rare secrets.** The crops keep the fast measurements and drop the slow, so a few slow ones
  vanish from them, and they hardly move the mean. A pool of 1,024 entries over 25,000 measurements gives each
  random entry about a dozen measurements; k measurements delayed by far more than the spread of the others give
  |t| on all measurements of about √k, whatever the delay (3.3 for eleven), and the crops drop them. The diagnostic
  above sees such an entry when it is slow every time it is
  measured, and only when the pool holds it; a secret the pools do not hold is not measured at all. Covering it
  takes a statistic of the tail (the slowest measurements of each class, compared) or inputs chosen to reach the
  rare case (scalars with runs of zero digits, carries at the limbs' edges).
- **Other secret steps of the node.** The reading of the key files (their text envelopes and hex, `secret.KeyText`),
  a VRF key's public key computed when it is read, and the checks of a KES key when it is read (which generate
  subtrees from their seeds and compare verification keys) run at start and at each reload, and are not timed.
  Neither is the comparison of a slot's VRF output with the leader threshold, nor KES key generation.
- **Other KES evolutions.** An evolution's work depends only on the period, which is public. The two timed reach the
  depth-0 case (0 to 1, copies and erasure only) and a depth-1 generation (1 to 2); a move into the right half of a
  deeper level generates a larger subtree with the same operations (31 to 32: 32 leaves) and is not timed.
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
-Xbatch` and a fixed heap). The build writes the checkout's `target/` directories and the dependency cache under
`.cache/`, and the image is Docker's; the runs write only their output directory. The job is stopped after 90
minutes, a run after 25, and a stopped run's container is removed within a minute or named as left behind. Each
run's record (`run-N.json`) is the tool's report with the run's identity, the commit, the JVM, the CPU and the tool's
exit status around it. The summary has every target's statistic in every run, the runner's CPU and the verdict; the
records, the outputs (`run-N.log`), the summary and the provenance (commit, image, JDK, CPU, limits) are kept as the
workflow artifact `secret-timing-<run id>-<attempt>` for 30 days and published nowhere.

The rule, which the program recomputes from each record's statistics against the report's exact roster of targets
and roles (a record whose own flags disagree, whose roster differs or whose classes hold fewer than 1,000
measurements is refused):

1. A run is **blind** when its variable-time control is not above 10: it could not see a leak that is there, and it
   votes neither way. Otherwise its **floor** is the largest of its random/random, same-seed and field-arithmetic
   controls' statistics, and the run is **clean** when the floor is not above 10, **noisy** when it is.
2. A secret target **counts** in a clean run when its statistic is above 10, and in a noisy run when it is above 10
   and above five times the floor: the rule `TimingProbeMain` applies to a target against its noise control (t above
   10 and above five times the noise). A control that failed says the machine was noisy at its level, not that a
   reading far above it is noise.
3. A **leak is confirmed** when one secret target counts in two runs.
4. Otherwise the result is **inconclusive** when any secret target's statistic was above 10 in any run, counted or
   not, in a run of any kind (the summary names the target and its readings), or when fewer than two runs were clean.
5. Otherwise **no leak was found**.

The confirmation adapts the method of Goodwill, Jun, Jaffe and Rohatgi ("A testing methodology for side-channel
resistance validation", NIST NIAT 2011, the methodology known as TVLA): a device fails when Welch's t exceeds the
threshold at the same point in time and in the same direction in two independent data sets, with 4.5 in their
procedure for AES. The differences here: the threshold is dudect's 10; a run's statistic is its largest over the
crops, so two runs may confirm at different crops; the direction is not compared, since the targets whose fixed
secret is drawn once draw it anew in each run and a leak may then be slower in one run and faster in the other; and a
reading in a noisy run is counted against five times its floor. A difference that repeats but stays below 10 is not
seen at all: the job stays green.

| Exit status of `secret_timing.py` | Verdict | The job |
|---|---|---|
| 0 | no leak found | green |
| 1 | leak confirmed | red, with an error annotation |
| 2 | inconclusive (the devnet scenarios' convention) | green, with a warning annotation: neither a pass nor a failure |
| 3 | the check could not run (build, tool or record failed or refused, a file could not be written, the arguments are wrong, or the program failed) | red |

The job's last step (`--outcome`) is green only when the summary gives the status the test step exited with and
every run's record is there; a step that left no summary, or one that disagrees, is red whatever its status.

Why one run is not enough, and why 10 and not less. With the classes interleaved at random, |t| of two classes of
one distribution is close to a standard normal, and above 10 by chance essentially never, even as the largest of a
target's crops. What makes a fixed-against-random |t| large without a leak is systematic: the harness (a fixed input
that lies in memory differently from the random ones, which is why the pools are made alike, take turns and are staged
into one buffer, and what the same-seed control watches) and the effect of a repeated value on the processor: the same
arithmetic on the same values, again and again, may take a slightly different time from the same arithmetic on
changing values, which the field-arithmetic control measures and which says nothing of which value. A busy runner
adds noise that cropping removes only in part.

**The CPU.** Each record names the CPU model its runner had (as [conformance](conformance.md) records it for
`uplc-turbo`), and the summary says whether it is the reference CPU of the timing scenarios (`TURBO_REFERENCE_CPU`
in `scripts/conformance/scenarios.py`). The verdict does not require that CPU, unlike the `uplc-turbo` score: a run
compares its two classes, and its controls, on one machine in one JVM, and decides nothing from a number measured
elsewhere. That is an argument from the design. A statistic's size does move with the CPU, so the nightly summaries
are read per model, and a model whose controls fail night after night, or whose secret targets are above 10 with no
change to the code, is the evidence that would restrict the check to some models.

## What a result means, and what to do

- **Leak confirmed.** One secret target, the same one, counted in two runs. Read the summary: which target, how far
  above, and the floor of each run. Find what changed since the last night that was not red: the vendored curve code,
  the `secret` packages, `Sum6Key`, `Leaf` or `Draft03Key`, the JDK image (a new JDK compiles differently), or
  `SecretPathTiming` itself. Dispatch the workflow again (with five runs), and run it by hand on an idle machine. A
  leak that repeats there is a finding about a secret: report it as [SECURITY.md](../../SECURITY.md#reporting) says,
  and fix the code, not the check; the threshold, the rule and the sizes are not changed to make a run pass.
- **Inconclusive.** Either a secret target was above 10 without being confirmed, or fewer than two runs were clean;
  the summary says which, names the readings and the control that failed in each run. It is not evidence either
  way. A reading above 10 once: dispatch again and see whether the same target comes back. `control-random` above
  10: the runner was disturbed; dispatch again. `control-field` above 10: this CPU shows a value effect at this size;
  if it persists for one CPU model, record it in an issue, since on that model the check sees only readings five
  times above it. `control-vartime` not above 10: the run could not see a gross leak, so something in the runner or
  the JVM changed how the test measures; that is a defect of the check to investigate. Inconclusive night after
  night means the check is not running in effect: file an issue.
- **No leak found.** No secret target above 10 in any run, and at least two clean runs: a bounded negative
  observation for this size, this runner and this JVM.
- **The check could not run.** The build, a run, a record or a summary failed; the step's log and `build.log` and
  `run-N.log` in the artifact say where. Fix it like any red nightly.

## Running it by hand

On an idle machine, from the checkout, with Docker (the program never runs java or sbt on the host):

```sh
python3 -I -B scripts/secret_timing.py --out "$(mktemp -d)" --build-image
```

`--repetitions` and `--measurements` change the size, `--classpath-file` takes a tools classpath already exported
instead of building, and `--dry-run` prints the commands. To decide across the artifacts of several workflow runs,
unpack each into a directory of its own and give their records together; a record given twice, or one whose tool
did not exit 0, is refused, and records of different commits or sizes are decided together only with
`--allow-mixed`:

```sh
python3 -I -B scripts/secret_timing.py --out "$(mktemp -d)" --reports run-a/run-*.json run-b/run-*.json
```

The tool alone, in the JDK image, prints its table and the run's own assessment, and with a path writes the report:
`java -XX:+UseSerialGC -Xbatch -cp <tools classpath> xyz.kd5ujc.scarab.crypto.vrf.SecretPathTiming [measurements
[report]]` (the export of the classpath is in performance section 67).

**Showing that it can fail.** After a change to the check, put a secret-dependent step into a multiplication the
targets use, run the program, see the targets that take that multiplication confirmed as leaks and the others and
the controls not, then restore the source and check that its diff is empty. Such changes were tried when the check
was built, in a container on a developer's machine (the pull request has the numbers):

- a return at once from `EdwardsPoint.multiplyRadix16` and the basepoint table's `multiplyRadix16` when the scalar's
  top digit is even: every secret target that multiplies was confirmed, and the evolution from period 0 to 1, which
  does not, was not. The same-seed control rose with it, close to 10, since it signs with the code that leaks;
- a shortcut that returns the identity for a zero digit from the variable-base table's `select`, a change one might
  make for speed: proving, the output and the two `[k]H` targets were confirmed, and the targets that do not take that
  table were not;
- the first with the field-arithmetic control made to fail on purpose (an extra squaring for its fixed element), so
  that no run was clean: the readings above five times the floor counted and confirmed the leak, and those between
  10 and five times the floor did not count and were named.
