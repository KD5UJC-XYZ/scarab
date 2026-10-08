# Isolated local validity-interval observations

`scripts/private_cluster_interval.py` reuses the bounded transfer runner and the
scenario runner's complete submitted-CBOR digest, separate streams, command binding
and stable paused-state guards. It uses the relay-only transfer path, disabling the
other scenario's wrong-key/repeated/conflicting submissions. The existing cleanup,
watchdog, local Docker endpoint, binary hashes, internal networking and resource
limits are retained: 3 CPUs / 6 GiB reference plus 1 CPU / 1 GiB Scala, one owned
cluster, 300–480 seconds of workload and 600 seconds overall. No ports are published
and no public peers, production keys or real funds are used.

After the existing observed relay peer/request/delay readiness and bounded early-epoch readiness and the checked positive pre-snapshot, the
actual observed pre-tip slot `s` determines three distinct transaction bodies:

- Positive: lower `max(0, s - 100)`, upper `s + 1000`.
- Expired: absent lower, upper `s - 1` (`s` must be at least 2).
- Not-yet-valid: lower `s + 100000`, absent upper.

The two negatives use the same unspent input, outputs and fee as the positive
transaction and are independently signed before submission. They are submitted
first while producers stay paused. The expected reference constructor is
`OutsideValidityIntervalUTxO`, with nonzero process status in one captured stream;
unexpected acceptance, transport failures or other rejection categories fail
closed. Original signed CBOR is saved and SHA-256-bound to submission receipts,
with file stability checked around submission. Whole-UTxO, parameters, fee pot
and tip observations are bracketed; none is claimed to be an atomic snapshot.

These are separated interval cases, not an exact lower/upper boundary experiment.
The reference mempool clock can advance while producers are paused. Raw errors
are retained privately; a paused tip or wall-clock estimate is never labeled the
reference evaluation slot. Receipts explicitly set
`referenceEvaluationSlotEstablished: false` and `exactReferenceBoundaryProof: false`.
The post-snapshot slot is likewise never used as the positive transaction's
inclusion slot. The applied Scala command locates its exact original body/witness
bytes in a committed captured block, evaluates that block's slot and reports the
outer `conway-pv9-cluster-ada-interval-transition-v1` profile. The harness checks the
reported bounds, slot source, transaction identity and profile before success.

The positive path uses the existing relay-only runner, including observed peer
readiness and relay/producer admission evidence. Its no-op producer fallback
records the route without a second submission; the relay guard forbids producer
submission. Positive results cover only the documented interval,
minimum-output, witness, restricted transition, original-byte inclusion and fee
predicates. Unsupported features remain rejected. No full-ledger, complete
failure-order, header or consensus validity claim follows.

## Offline and retained evidence checks

Public Python discovery already includes `test_private_cluster_interval.py`, with
eight guards for separated bounds, uint64 limits, exact build interception,
unchanged input/output/fee arguments, fail-closed negatives, containing-block
receipt validation isolation from the other scenario path, and the relay-only route. The worker's
13 Scala interval tests cover original unstripped identity/signatures/fee size,
boundary values, range binding and downstream failure precedence.

`CLUSTER_INTERVAL_EVIDENCE` enables six tests in `ClusterIntervalEvidenceSuite`.
They run the interval-aware composition on the original captured packet, compare
the applied command's receipt with its derived containing slot, bind negative
transaction digests/commands/error categories to the preserved submissions, and
check negative states against the checked positive pre-state. Their negative
pure-predicate check uses an explicitly supplied observed tip; it is not reported
as the actual reference admission slot. Legacy retained tests remain pointed at
their original interval-free packets and their legacy public APIs remain closed.

Keys, original transactions, cluster state, raw error streams and test logs stay
outside Git.


## Preserved first run

The first run stopped after the relay's positive submission when the inherited
direct-producer fallback returned the all-inputs-spent mempool precheck. This is
preserved as a failure, not successful inclusion evidence: the interval-aware
capture comparison had not yet run. Both negative submission observations and
all original receipts remain at `/home/<user>/cardano-interval-live1-20261008`.
The run took 98.89 seconds and verified empty owned-resource cleanup. The interval
runner now composes the existing relay-only readiness/admission path, preventing
that second positive submission. The failed run remains unchanged.

## Completed integration checkpoint

The second isolated run passed in 227.11 seconds within its 420-second workload
and 600-second overall limits. Its observed pre-tip slot was 1028. The positive
transaction's interval was `[928, 2028)` and its actual containing-block slot was
1281. Original body/witness inclusion, signatures, minimum outputs, the complete
restricted UTxO transition and fee-pot delta 200000 all passed. Seven unrelated
UTxO entries were unchanged. Relay-only submission and producer admission were
observed separately from the committed-block inclusion check.

The expired body had upper bound 1027; the future body had lower bound 101028.
Both returned `OutsideValidityIntervalUTxO` with stable bracketed paused-state
observations and original submitted-CBOR bindings. These observations establish
neither the reference evaluation slot nor exact-boundary or failure-order parity.
Owned-container and owned-network cleanup was empty. Private receipts are retained
at `/home/<user>/cardano-interval-live2-20261008`; the first failed run remains intact.

Applied-command offline validation passed 800 public Scala tests, 25 public gates
and 58 Python guards. A separate retained-data run passed 39 tests: six new
interval checks and 33 legacy transfer/negative checks on their prior packet.
These optional evidence tests are reported separately from public totals. The
integration receipts and logs are at
`/home/<user>/cardano-interval-integration-20261008`. Full ledger and consensus
validation remain outside this profile.
