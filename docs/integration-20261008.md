# Offline integration checkpoint, 2026-10-08

Integrated the reviewed minimum-output predicate (ceddecb442722e1272ff232cf25af124761e7bf0), follower (upstream 6223e0050afb14c73c4c016355d2a095feda9e61, local d97bcb6), follower capture adapter (upstream e3a2abd1dfba1bce4f7081ec981b14fe1a78e97c, local ffb86e7), and experimental header adapter (upstream c8cb79c8467d5256a4dd6f76879a7edb988e7b96, local a528e69941954a56aef11f910799ec20934175a9). No scenario adapter is included.

At a528e69941954a56aef11f910799ec20934175a9, formatting and all **764 public Scala tests** passed: core 197, VM 13, network 100, network-runtime 52, ledger 105, ledger-runtime 150, fetcher 5, app 142. All **25 serial public gates** and **24 Python launcher guards** passed. Private corpus suites and six private gates were not run.

A separate offline run passed **28 retained-data tests**: two real captured-header OpCert/KES observations and 26 context/transfer/inclusion regressions. The standalone minimum-output command accepted the prior captured 10 ADA transfer, measuring destination 37 bytes (required 849070) and change 41 bytes (required 866310). This does not test reference admission at the minimum boundary. Private test source and captured bytes stayed outside Git and were mounted read-only.

Both runs used local Linux Docker, network disabled, 4 CPUs, 4 GiB memory/swap cap, 512 PIDs, read-only container root, dropped capabilities, no-new-privileges and user 1000. The existing image is sha256:ce5dd881ba207fb485aaebd9bb065ac79a808f26ff52467eca938dd064994203, derived from the pinned official JDK image. Builds ran serially with the inspected existing cache. Containers were removed. Commands, logs and retained-source hash are outside Git under /home/<user>/cardano-integrated-offline-20261008.

## Interpretation and next live checks

- Minimum-output is a restricted Conway 9.0 ADA predicate. The paired 849070/849069 reference submission has not run; direct authorization is pending after an approval/UI blocker.
- Follower checkpoints describe acquired candidate bytes, not an adopted ledger tip. Freeze an immutable checkpoint before creating a store/replay source: extensions and forks both change source identity. Its adapter injects a local exception after intersection; it does not simulate a mid-packet failure or reference-node crash. No live follower result is claimed.
- Header `Right` means an observation was produced. Check OpCert and KES results individually; rejection can occur inside Right. Genesis/registration context is supplied, with VRF, stake, nonce, counters, leadership and consensus still unchecked. Retained positive signatures do not prove crypto acceptance-set parity.
- No new live workload, public peer connection, key creation, transaction submission or public push occurred during this integration. The last hosted-green public revision remains cec73075948a696cfe76313f80df0138535305f2; these integrated commits have only local acceptance so far.


## Subsequent scenario integration

The earlier checkpoint above is historical. Hosted CI subsequently passed at
`db88afbd2b7d86517d8d4f0f5176ca650d3a369a`. The separately reviewed scenario chain is
now integrated, with **778 Scala tests, 25 public gates and 42 Python guards** passing
offline; see [the public verification record](public-profile.md#scenario-integration-acceptance-2026-10-08).
The negative adapter observes missing required-key coverage or unresolved inputs;
it does not establish invalid-signature or invalid-block rejection. State snapshots
remain non-atomic. Cancellation/restart tests mean a fresh harness run, not recovery
of a retained reference-node database.

The user directly approved disposable local keys, isolated local Docker clusters
and local test submissions in the replacement conversation. Public-network
submissions and real funds remain forbidden. Live results are recorded separately
in [minimum-output verification](minimum-output.md); authorization itself is not
test evidence.


## Completed replacement-task checkpoint

The authorized isolated minimum-output pair passed: 849070 lovelace included,
849069 rejected with the required 849070 boundary. The corrected scenario packet
also passed, followed by the complete offline negative comparison. Final local
counts are **779 public Scala tests, 25 public gates, 50 Python guards**, and a
separate **33 retained-data tests**. The independently reviewed follower follow-up
is integrated, with its bounded acquisition/local injected-disconnect evidence;
no reference-node crash or mid-packet failure is claimed.

Review of the scenario correction and shared convergence guard was performed by
`/root/scenario_review`; the reviewed precommit diff against `2eda20c` had SHA-256
`24cc472b9c53e768201ab9749d5e71c5b09a5d299c6d249b8df53187ebe578d6`.
The scenario and minimum-output evidence, prior failures, command logs and
review receipts remain outside Git. All resources owned by these local runs were
verified removed. Other workers retain their own worktrees and resource ownership.


The subsequent reviewed header-context integration (`34fa905`) brings the current
full public result to **787 Scala tests, 25 gates and 50 Python guards**. Its
standalone observation on the newly captured packet and all nine header tests
(eight public plus one opt-in) passed separately. See the latest
[public verification checkpoint](public-profile.md#header-context-integrated-checkpoint).


## Applied validity-interval checkpoint

The command now uses the explicit
`conway-pv9-cluster-ada-interval-transition-v1` composition, including the actual
containing-block slot and original unstripped identity/signatures/fee sizing.
Legacy Coverage, Balance and RestrictedReplay source remains unchanged. The
isolated reference run accepted an interval-bearing transaction at slot 1281
within `[928, 2028)` and rejected separated expired/not-yet-valid bodies. This is
not an exact-boundary, complete failure-order, full-ledger or consensus proof.
Validation: 800 public Scala tests, 25 public gates, 58 Python guards; separately,
39 retained-data checks. See [the interval evidence record](private-cluster-interval.md)
for scope, private receipt locations and the preserved initial failed run.


## Certificate-state capture checkpoint

The experimental certificate state and dedicated source-bound capture now verify
OpCert/KES signatures, required pool-to-VRF registration, exact Word64 counter
transitions, full final counters and every-prefix rollback/reapply over an
original four-block range (anchor slot 1024 to endpoint slot 1180, epoch 2).
Observed counters did not change. This remains narrower than full consensus;
VRF eligibility and stake/nonce evolution are not checked, and state exports are
separate non-atomic acquisitions. See [the certificate capture record](private-cluster-certificate.md).
Validation: 817 public Scala tests, 25 public gates and 66 Python guards; separately
four optional retained-data checks (11 suite tests including seven public reruns).
