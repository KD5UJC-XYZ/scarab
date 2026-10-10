# Single-service repeated endpoint prerequisite

`scripts/private_cluster_plutus_repeated_capture.py` is a separate, opt-in
prerequisite lane for the repeated terminal comparator. It reuses the existing
single-service funding, original two-spend HTTP client, native initial packet,
exact historical endpoint acquisition, and immutable owned-container cleanup.
It does not launch or enable the two-service/restart soak. The unconditional
`private_cluster_plutus_soak.require_comparator()` guard remains in place.

The controller requires both `--epoch-mode repeated-jvm-v1` and
`--terminal-epoch 0|1`. It is preflight-only unless `--execute` is present.
All existing pinned support manifest, projection, immutable image, fresh private
owned/evidence roots, Compile-only runtime classpath, and separate Test client
classpath arguments remain required:

```sh
python3 scripts/private_cluster_plutus_repeated_capture.py \
  --support-manifest /absolute/reviewed-support.json \
  --support-sha <reviewed-support-sha256> \
  --owned-root /absolute/fresh-private-owned-root \
  --evidence-root /absolute/fresh-private-evidence-root \
  --scala-build-root /absolute/compiled-checkout \
  --scala-classpath-file /absolute/runtime-classpath.txt \
  --client-classpath-file /absolute/test-classpath.txt \
  --projection /absolute/pinned-projection.py \
  --scala-image sha256:<immutable-jdk-image-id> \
  --epoch-mode repeated-jvm-v1 \
  --terminal-epoch 0 --duration-seconds 30 --max-blocks 128
```

Use reviewed local values, not the illustrative placeholders above. Add
`--execute` only for an authorized disposable local cluster run. No public relay,
real funds, persistent environment reuse, native likelihood process, checkpoint
restore, or tested-spend CLI submission is introduced. There is still exactly
one reference-only funding submission before bootstrap.

## Limits and scope

- One Compile-only pure-JVM service, one reference namespace and serial helper
  processes. The aggregate ceiling remains **4 CPUs / 7 GiB**. Runtime and helper
  mounts, immutable identities, ownership tokens and cleanup are inherited.
  Process identity and frozen-point guards remain enforced.
- **300 seconds maximum operation, plus 30 seconds owned cleanup**. Startup checks
  reserve 30 seconds peer readiness, the full requested active interval, 10
  seconds finalization, 57 seconds exact capture and 35 seconds comparator work.
  A missed preparation or remaining-stage budget fails closed. Existing
  slot-100 prefunding and slot-300 frozen bootstrap windows are unchanged.
- Active duration is 10–120 seconds, with 1–128 published blocks. A successful
  prerequisite must finish the entire requested active duration; a block-limit
  stop is not an alternative success. The separate startup deadline does not
  consume that active interval.
- The original short client uses at most 30 seconds. Both original spends retain
  TTL 999 and must be accepted and included in epoch zero under the same owner.
  A post-boundary terminal comparison is **not** evidence of post-boundary HTTP
  admission.
- Terminal epoch is declared in advance and checked against the actual final full
  point, state pin, component epoch and transition count. `0` is explicitly
  `epoch-zero-only`; `1` is `post-boundary-exact-epoch-one`. A run ending in another
  epoch fails. Duration is a bound, not a guarantee that a desired epoch is reached.
- The restricted exporter and comparator now have a locally tested
  [exact active reward projection](repeated-active-reward-comparison.md), including
  the uncompleted cursor and recent rewards. Independent native/live acceptance
  remains pending. The controller still accepts only `absent` or `complete` reward
  phases; this local change does not remove that guard. There is no polling for a convenient replacement terminal state,
  latest-state fallback, endpoint substitution, or in-runtime native checking.

## Evidence

Preparation reads each active process's `stat`, NUL-delimited `cmdline`, and
`stat` again in one fresh container-side command. Both identity observations must
pass the pinned validator and equal the saved PID/start-ticks/argv; no result is
cached between checks. This removes one Docker exec per identity check and also
rejects process replacement across the batch. It is not an atomic observation.
Both process-role brackets, both final tips, separate state queries, and all
slot-100/slot-300 deadlines remain unchanged.

`preparation-timing.json` records per-command elapsed time, preparation stage,
remaining budget and requested timeout. Its dispatch timeout is explicitly an
upper bound: the inherited launcher clips it again at subprocess dispatch.
Payloads, command outputs and unrestricted error text are excluded. The receipt
is written on success or failure without replacing an existing failure; a
`body-completed` receipt does not prove preparation acceptance. The final window
check also runs after receipt writing. These offline-checked changes do not
establish that a future live run will fit the preparation budget, and do not
replace or reclassify previous failed evidence.

The runtime must identify pure-JVM repeated mode in bootstrap, active and final
records. Original client bodies/witnesses, all publication hashes, full owner pins,
JVM generation request/output hashes and exact inclusion observations are checked.
An epoch-zero run before the first freeze may have no JVM generation; this is
recorded as empty evidence and never becomes a parity or post-boundary claim.
A post-boundary run requires selected original JVM generation evidence.

`terminal-epoch-proof.json` records the exact selected export, declared and actual
epoch, reward phase, active record hash and publication proof hash. It deliberately
has `nativeEndpointAgreement=false`: exporting a state is not a comparison.
After the service/client exit, the reference remains running while the existing
capture path acquires the exact terminal slot/hash/block number. The separate
Test helper invokes `lab.PlutusRepeatedServiceCompareMain` against the pinned
initial packet, endpoint acquisition and both original signed transactions.

`endpoint-comparison.json` has the strict
`plutus-repeated-service-endpoint-comparison-v1` schema and binds the complete
terminal pin, source/initial manifest, original terminal observation/output map,
endpoint manifest/acquisition receipt and native whole-UTxO bytes. It verifies
complete supported UTxO effects, collateral, stake/snapshots, epoch/governance and
reward components, raw non-myopic IEEE bits, actual fee pots and represented
protocol fields. Restricted active pulser fields can now be compared exactly by the
local comparator, while malformed or unsupported native effects fail rather than
being projected away. The controller's active-phase refusal and historical failed
receipts remain unchanged pending independent native validation and separate guard
review. The final controller result is emitted only after
this comparison, original evaluation-receipt checks and verified cleanup.

This prerequisite makes no full-ledger, late-restart, multi-service, native
likelihood parity, post-boundary HTTP spending, or full-soak claim. Compilation,
focused offline tests and source wiring alone are not native endpoint agreement.
A retained successful live receipt for its exact source and declared epoch is
still required; an epoch-zero receipt cannot satisfy a post-boundary gate.

## Offline checks

```sh
python3 -m unittest discover -s scripts -p 'test_private_cluster_plutus_repeated_capture.py'
python3 -m unittest discover -s scripts -p 'test_private_cluster_plutus_service.py'
python3 -m unittest discover -s scripts -p 'test_private_cluster_plutus_soak.py'
python3 -m unittest discover -s scripts -p 'test_private_cluster_plutus_preparation.py'
```

The focused tests cover unchanged default dispatch/epoch guard, explicit mode and
resource bounds, complete timing, declared epoch/full-point mutations, active
pulser refusal, original hashes, exact comparator schema, epoch-zero evidence
scope, serial oracle cleanup and the still-blocked full soak.
