# Concrete two-service HTTP client

`lab.PlutusMultiEndpointClientMain PORT1 PORT2 DURATION_SECONDS EXCHANGE1 EXCHANGE2 OUTPUT`
is a Test-only concrete client for two supervisor-owned local Scala services.
Duration is 3–60 seconds including original reads, connection, submissions and
cross-observation. The supervisor supplies distinct ports and private exchange
directories. Each exchange contains `submission/transaction-1.cbor`, its own
`bootstrap-ready.json` and its own original `publication-NNNN.json` files.
The output directory is separate and writable; service exchanges can be mounted
read-only. No cluster creation or reference submission is performed by this JVM.

The client checks four disjoint spending/collateral references across the two
original CBOR transactions. It observes actual endpoint owner IDs and delegates
the two distinct ingress attempts to the reviewed typed boundary. Transaction one
enters only service-1; after its accepted envelope and later inclusion are checked,
transaction two enters only service-2. An accepted transaction is never an inclusion
claim. Only explicit non-admitting `Unavailable`/`StaleState` replies may be retried;
uncertain transport failures are not submission retry authority.

Each designated inclusion matches its endpoint's HTTP full pin and original
publication, including original body and witness hashes. Publication source-join
and initial-manifest bindings must equal that endpoint's readiness record.
Before success, both original transactions must also appear in both endpoint
publication streams. These cross-observations use publications because a service
need not track HTTP status for transactions submitted through the other service.
All proofs advance beyond that endpoint's initial point, and final state covers
them without an equal-coordinate hash substitution. Endpoint snapshots and
publications are separate acquisitions, not an atomic cross-node state view.

The transport uses fixed loopback addresses, no proxy or redirects, bounded HTTP
responses (64 KiB), bounded original publications (128 KiB, at most 128 filenames)
and three-second request/body deadlines. Each Java 21 HTTP client has an owned
two-thread bounded executor. Resources cancel/shut down both the client and
executor, each with a five-second termination wait. Deadlines are cooperative;
cleanup can extend elapsed time. The enclosing supervisor remains responsible
for node ownership, overall process deadlines and disk/log limits.

`OUTPUT/multi-endpoint-client-result.json` is published without overwrite through
a same-filesystem hard link, capped at 1 MiB. Successful `endpoints` rows retain
designated accepted/included responses and original publication hashes, both
cross-observed transactions, and initial/final scoped states. `resourcesFinalized`
confirms client resource finalization only, not node cleanup. On failure it is
conservatively false; bounded partial endpoint replies, accepted/included evidence
and a closed failure category remain diagnostic. Oversized evidence becomes a
bounded failed record and a failing exit code. No fsync/crash-durability claim is
made. Cancellation does not synthesize success.

This implementation and loopback HTTP regressions are not live two-node evidence.
The existing single-service runner still blocks `MultipleNodes`. Actual topology
acceptance requires the independent supervisor, two Compile-only Scala owners,
original endpoint-file checks, and two independently bound historical endpoint
oracles. Public-network activity, restart authority, repeated epochs and full
ledger/consensus validation remain outside this client.
