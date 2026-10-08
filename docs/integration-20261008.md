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
