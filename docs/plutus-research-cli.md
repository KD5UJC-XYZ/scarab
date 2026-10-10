# Restricted Plutus research CLI

The explicit `plutus-research` command packages the bounded PV9 Plutus diagnostic
composition in the application runtime. It does not enable a general node,
arbitrary scripts, public-network operation, durable pool recovery or complete
ledger/consensus validation. Existing default commands do not start this profile.

## Invocation and external contract

```sh
bash scripts/sbtw app/runtimeClasspathFile app/packageBin
java -cp "$(cat app/target/runtime-classpath.txt)" lab.Main plutus-research --help
```

The checked invocation has six explicit option pairs:

```text
plutus-research
  --profile isolated-conway-pv9-plutus-v3-spend-v1
  --initial ABSOLUTE_CHECKED_BUNDLE_DIRECTORY
  --manifest-sha256 LOWERCASE_64_HEX_SHA256
  --port OWNED_REFERENCE_PEER_PORT
  --magic ISOLATED_NETWORK_MAGIC
  --exchange ABSOLUTE_EVIDENCE_EXCHANGE_DIRECTORY
```

These placeholders are a configuration contract, not a ready-to-run cluster.
The command requires the existing checked native bootstrap bundle, immutable
transaction descriptor and original-byte pins, and the external peer/endpoint
exchange protocol. The descriptor identifies expected evidence; it is not an
ingress channel. Signed transactions still enter only through loopback HTTP.
The peer port and ephemeral HTTP API port are different; readiness publishes the
latter. The API routes and pool semantics remain those in the
[submission contract](ada-submission-implementation.md#api-and-identity).

The current command is an epoch-zero, testnet-ID-zero, 1,000-slot/100ms diagnostic
with the existing bounded follower, relay attempts and cooperative 220-second
overall timeout. Blocking filesystem completion is not a hard real-time guarantee. It stops after verified inclusion and endpoint comparison. It retains
the external descriptor and controller protocol; it is not an unattended
long-running node service. The reference acceptance setup still explicitly uses
zero TxSubmission initialization delay. Default-delay ingress remains a separate
interoperability gate.

The runtime classpath must contain Compile artifacts only. The separate isolated
HTTP test client may use a Test classpath, but the controller must not mix it into
the node runtime. The controller requires separate `--scala-classpath-file`
(Compile runtime) and `--client-classpath-file` (external Test client) inputs.
`transaction-originals INPUT OUTPUT` is a Compile-only helper
for bounded structural identity and original-span hashes. It creates evidence
without replacing an existing output. It does not validate ledger admission.

## Checked evaluation evidence

The optional evidence observer records the actual checked admission evaluation;
it does not rerun the script to manufacture a receipt. Evidence binds the original
transaction/envelope/body/witness identities, checked state/environment and
validation slot, request/context/script/model hashes, fixed evaluator semantics,
and declared and consumed execution budgets. The outer record binds the acquired
source join and bootstrap manifest. Original context bytes and keys are not
published by this evidence interface.

Historical pool acceptance, duplicate evaluation and later revalidation are
distinct observations. The original accepted HTTP evaluation must be selected
explicitly; the most recent evaluation is not a substitute. The file observer
uses a fresh directory, at most 128 records of at most 16 KiB each, and
no-overwrite publication. Cancellation waits for an in-progress filesystem write
to finish; no detached writer is allowed to outlive the owner fence or resource.
This bounds work and storage, not filesystem latency. An evaluation receipt
does not prove subsequent inclusion, full ledger validity, or correspondence to
some other state point. Inclusion still needs independently observed original
body/witness equality and the endpoint comparison.

## Verification and evidence history

The public Compile-only CLI gate checks explicit configuration refusal, original
span identity, output preservation and malformed/oversize rejection without a
cluster or transaction submission. Scala tests separately exercise lifecycle,
state/evidence bindings, cancellation and failure behavior.

The earlier [local acceptance receipt](../reference/plutus-admission/live-receipt.json)
predates this CLI and standalone evaluation instrumentation. It is preserved
unchanged and cannot establish these later features. New live acceptance, if
performed, must name the exact tested source and runtime artifacts, capture the
standalone receipt and distinguish it from the earlier run.

See [current capabilities and next phases](private-validator-status.md) for the
remaining script, epoch, persistence and consensus gaps. All live scenarios use
isolated local Docker clusters, disposable keys and no real funds; private
captures, logs and cluster state stay outside Git.
