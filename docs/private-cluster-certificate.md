# Isolated local certificate-state capture

`scripts/private_cluster_certificate.py` uses the existing isolated Docker
lifecycle, relay setup and paused-producer snapshot helpers. It does not call the
transaction-producing transfer scenario, and rejects transaction build/sign/submit
operations. One reference container uses 3 CPUs / 6 GiB; its Scala process uses
1 CPU / 1 GiB. Unique owned resources, internal networking, disposable keys, no
published ports, watchdog and cleanup remain inherited. Workload is 300–480 seconds
(default 420), with a 600-second overall limit.

The harness waits for a complete converged early-epoch Conway point, pauses both
producers and retains original tip brackets, genesis, protocol counters, full ledger
state and protocol parameters. It then resumes for at least four blocks, pauses
again and retains the same original exports at the endpoint. The observed range
must contain 4–8 blocks in one epoch. Overshoot, changed epoch and incomplete
sources fail closed. The existing full ledger/protocol exports retain any nonce or
stake fields they contain; this certificate check neither derives nor validates
nonce, stake or VRF eligibility from them.

The dedicated `certificate-context-v1` manifest binds nine original files by
SHA-256. `lab.CertificateCapture` independently verifies these digests, agreeing
tip/slot/block/epoch brackets, each protocol export's `lastSlot`, ledger epoch,
genesis timing/network, unchanged parameter bytes, ledger PV9 and unchanged
pool-to-VRF registration mappings. The entire actual counter map is seeded from
the pre-export, retaining entries outside the current pool distribution. Missing
exports never become empty maps. Registration comes from
`stakeDistrib.unPoolDistr`, not the genesis pool list.

While producers stay paused, the adapter acquires the exact anchor-to-endpoint
ChainSync/BlockFetch range and preserves original envelopes and blocks. It builds
the checked acquisition branch, verifies both OpCert and KES signatures and each
issuer's registered VRF-key hash, applies the pure certificate transitions, and
compares the complete final counter map and exact endpoint with the reference
export. Every retained prefix is rolled back and reapplied, requiring exact
restored state identity and deterministic final identity, map and point.

The narrower required-membership policy and Word64 counter behavior are unchanged
from [the approved certificate-state packet](praos-certificate-state.md). Equal
counters are accepted. If the observed maps remain equal, the run demonstrates
equal-counter transitions, not a live counter increment; increment cases remain
separate synthetic tests. No snapshot is represented as authenticated or atomic:
each CLI export is a separate acquisition under observed producer pause and stable
tip brackets, and protocol `lastSlot` alone does not authenticate a block hash.

The report remains `experimental-praos-certificate-state-v1`. Full ledger,
consensus, VRF eligibility, stake/nonce evolution and registration evolution are
not validated. Source and relevant app/core class digests are retained privately.
`CERTIFICATE_CAPTURE_EVIDENCE` enables optional tests of a retained original packet;
raw state, transactions, keys and logs are excluded from Git. Completed integration evidence is recorded below.


## Completed four-block reference observation

The isolated run completed in 227.47 seconds, with successful post-comparison
growth from epoch 2 to epoch 4 and empty owned-container/network cleanup. The
anchor was block 55, slot 1024, epoch 2; the endpoint was block 59, slot 1180,
epoch 2. Four original headers/blocks were acquired, starting at slot 1072.
Both OpCert and KES signatures, pool/VRF registration, the complete final counter
map and exact endpoint passed. Every retained prefix restored its exact prior
state and reapplied deterministically.

The observed counter change count was zero. This is equal-counter application and
rollback evidence; no live counter increment is claimed. Public synthetic tests
cover counter increments, stale/jumped counters, Word64 overflow and absence versus
zero restoration. No transactions were submitted by this certificate scenario.

Integrated offline validation passed 817 public Scala tests, 25 public gates and
66 Python guards. The optional retained suite passed 11 tests: seven public tests
rerun plus four additional retained-data checks. Those four optional checks are
not added to the public total. Independent review checked all nine manifest
digests and the 11 app / eight core compiled-class fingerprints against the build.

Private original evidence: `/home/<user>/cardano-certificate-live1-20261008`.
Private integration/test receipts: `/home/<user>/cardano-certificate-integration-20261008`.
The old follower corpus still lacks its own anchor-bound state; this new packet
does not retroactively repair or seed that earlier corpus. The separate exports
remain non-atomic, source-bound observations, not authenticated consensus snapshots.
