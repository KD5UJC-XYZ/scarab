# Funded private native-script spending

This explicit isolated fixture reuses the empty-Byron-allocation genesis profile,
preserving all Shelley funds and staking. Before funding, the complete reference
UTxO must match all six genesis allocations. A separate reference-only transaction
funds one enterprise signature-script output with 20,000,000 lovelace. Its original
signed CBOR, full pre/post states and fee change are retained separately. Creating
that script output is not claimed as Scala-validated setup.

The measured spend consumes that funded output, supplies exactly its native script
and an original-body key signature, and creates two ADA key outputs. The reference
submission goes only through the non-producing relay. The Scala observer acquires
the original ChainSync/BlockFetch range, checks exact included body and witness
bytes, derives the whole supported prestate with ClusterTransition, and only then
compares the reference post UTxO and fee pot. Acquisition remains distinct from
validated state; this command does not publish a CoherentBranch tuple.

Before acceptance, each of three complete rejected transactions is paired with an
otherwise valid control sharing the same body identity. They omit the script,
replace it with another script, or use a valid unrelated signer. Each case has a
separate short producer pause; Scala diagnostics run after resume against retained
pinned prestate. Reference growth is required between cases. The final accepted
spend has a fresh prestate and interval.
The pre-only Scala diagnostic checks MissingScripts, WrongScriptHashes and
FailedScripts respectively. Its receipt digest, retained original bytes and actual
submission digest must match. The reference must reject with the source-confirmed
missing-script or nonvalidating-script constructor. Original stdout/stderr is
retained; predicate order and exact reference evaluation slot are not claimed.
Complete UTxO bytes, tip and fee pot must remain unchanged under paused producers.

All state queries are separate acquisitions bracketed by observed quiescence,
not atomic snapshots. This is restricted ADA enterprise native-script spending
under supplied PV9.0 context, not full ledger/consensus validation, arbitrary
scripts, Plutus admission, script-output creation validation or persistent
validated-state recovery. No public network or real funds are involved.

The runner uses scripts/private_cluster_native.py with the explicit
conway-pv9-empty-byron-allocations-coherent-v1 fixture profile, the pinned reference
image and an isolated compiled Scala checkout. Reference resources are 3 CPUs/6 GiB;
one Scala observer at a time uses 1 CPU/1 GiB. Workload is at most 540 seconds within
the existing 600-second lifecycle limit. Existing relay restart infrastructure and
default key-transfer hooks remain unchanged.

CLI: `native-spending observe PORT DIRECTORY` or the offline
`native-spending diagnose PRE_DIRECTORY TRANSACTION_CBOR_FILE SLOT`. The diagnostic
uses only a five-source pinned prestate manifest and caller-supplied slot. Rejection
is exit 2; a derived diagnostic success is not reference acceptance.

## Completed bounded acceptance

The third retained attempt completed in 279.535 seconds, with empty owned-container
and network cleanup receipts. Reference-only funding changed the complete UTxO
from six to seven entries with a 200,000-lovelace fee. The measured native spend
changed seven to eight entries, consuming one and creating two; six untouched
entries retained their original spans. The derived fee delta was 200,000 lovelace.
One original containing block established body/witness inclusion at slot 1588.
Transaction: `10104c8954c8889f0b616659db35778af5bb9492007f8bbdc16040fef12c35e5`.
Original transaction SHA-256:
`0267bb2cc0010c6f8067e1b9a85c430cc59d5f814e8535f703d41996fe721bfb`.

All three negatives had successful prestate-derived controls with the same body
identity as their own rejected variant. Exact diagnostic/submission byte hashes
matched; reference errors and unchanged full UTxO/fees were retained. Reference
growth was observed after every separate pause. The positive spend matched its
independently derived complete poststate and fee pot. Final reference growth and
convergence passed. The receipt's requiredKeyCount is the count of direct key-input
requirements; the native signature predicate was checked separately.

Two earlier attempts remain preserved. Attempt one stopped before funding due to
a readiness clock sample taken before log reads; the sampling order now has a
regression test and future timestamps still reject. Attempt two completed all
negatives and delivered the accepted transaction to both producers, but prolonged
pausing was followed by Forge.Loop.NoLedgerView and no inclusion. Forecast
exhaustion is an inference from those logs, not an extracted internal exception.
The successful third attempt is a fresh cluster with shorter pauses, not recovery
of the stalled cluster. No failed observation was overwritten or relabeled.

Private receipts: /home/<user>/cardano-native-live1-20261009,
/home/<user>/cardano-native-live2-20261009 and
/home/<user>/cardano-native-live3-20261009. Integration checks and independently
recomputed summary remain in /home/<user>/cardano-native-integration-20261009.
Three opt-in tests use NATIVE_SPENDING_EVIDENCE to recheck each original negative
and control against its pinned complete prestate. Raw logs, transactions, keys,
cluster state and private receipts are excluded from the public source packet.
