# Incremental reward API oracle

**Status: Current** — offline reference evidence for [#135](https://github.com/KD5UJC-XYZ/scarab/issues/135).

The thin wrapper calls the unchanged released `startStep`, `pulseStep` and `completeStep`
functions re-exported by `Cardano.Ledger.Shelley.LedgerState`. It decodes real CBOR
`EpochState`/`BlocksMade` or a pending `PulsingRewUpdate`, and records every intermediate state
and reward event as CBOR. It supports the Shelley, Babbage and Conway era witnesses. This is an
API oracle on deterministic synthetic state, not historical chain replay or complete block admission.

The base is the [pinned context image](../plutus/context/README.md), whose frozen solver plan
selects `cardano-ledger-shelley 1.19.0.1`, core 1.21.0.0 and binary 1.9.1.0 used with reference
node 11.1.3. The relevant Shelley source revision is
`4c81e909df7555b6a58a000db8f8f4f89c7acdc4`; the wrapper imports public APIs and contains no
copied reward implementation.

From the repository root, build the base image and then:

```sh
docker build -f scripts/oracle/rewards/Dockerfile -t scarab-135-reward-oracle:local .
mkdir -p /tmp/scarab/reward-oracle
docker run --rm --user "$(id -u):$(id -g)" -e HOME=/work/.cache/home \
  -e SBT_GLOBAL_BASE=/tmp/sbt-global -v "$PWD":/work \
  -v /tmp/scarab/reward-oracle:/evidence -w /work scarab-jdk:local \
  bash scripts/sbtw 'storage/Test/runMain xyz.kd5ujc.scarab.storage.haskell.state.RewardOracleCases /evidence/inputs.json'
docker run --rm --network none --read-only \
  -v /tmp/scarab/reward-oracle:/evidence:ro \
  scarab-135-reward-oracle:local /evidence/inputs.json > /tmp/scarab/reward-oracle/results.json
```

`storage/testOnly *RewardOracleSuite` compares the retained outputs and checks that the fixture
producer still emits the recorded inputs. Compressed input and output resources, scope and
hashes are in [the fixture README](../../../storage/src/test/resources/rewards-1.19.0.1/README.md).
The comparison includes every encoded field without a likelihood mask: local generation uses the
reference's 100 Double sample positions/logarithms rounded to Float, and completion combines the
captured history with Float decay and normalization. Imported pending states retain their frozen
fields, including empty/new likelihood maps and the reward pot. Additional zero-production,
nonempty-history and decentralisation cases live in
[the likelihood corpus](../../../storage/src/test/resources/reward-likelihood-1.19.0.1/README.md).
Ordinary CI needs neither a Haskell compiler nor Docker access inside its JVM.

For #49's observable timing/accounting adapter, the same wrapper also imports the
unchanged native RUPD STS and `applyRUpd`. Generate `RewardTimingCases` instead of
`RewardOracleCases`, then run the same reference binary on its inputs. Optional
`rewardWindow` and `rewardSlots` fields select the exact reader window and slot
signals; original API-only inputs still work and their results are unchanged.
`RewardTimingOracleSuite` compares absence/pending/completion availability, complete
reward accounting and credited EpochState over dense, skipped-slot and forced
schedules. Its [fixture inventory](../../../storage/src/test/resources/rewards-timing-1.19.0.1/README.md)
records the independent source pin and exact execution hashes. `RewardSnapshotSuite`
adds native V5 storage continuation against genuinely observed Haskell prefixes.
These checks allow different internal pulse partitions and compare completed likelihoods as well
as monetary accounting. The conservative V5 provenance flag remains tracked by #129; it is not a
numerical mask in these API comparisons.
