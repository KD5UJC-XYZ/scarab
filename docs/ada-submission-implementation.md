# Restricted ADA submission implementation

Source checked on 2026-10-09. This implements the first bounded research profile
in [the submission contract](ada-submission-contract-v1.md):
`isolated-conway-pv9-ada-vkey-v1`. The HTTP adapter, service and mutation owner
are app-private composition APIs. The executable scenario is Test-only. This is
not a general-purpose Cardano node or full ledger/consensus validator.

The isolated ADA scenario passed on 2026-10-09: HTTP admission, Scala-owned
TxSubmission2 delivery, follower inclusion, pool removal and exact endpoint
comparison across the epoch boundary. The reference profile explicitly sets
`TxSubmissionInitDelay: 0`; default-delay interoperability remains unproven.
See the acceptance record below and the [separate native boundary experiment](native-boundary-diagnostic.md).

## API and identity

`AdaHttp.server(AdaHttpHandler(service))` binds only `127.0.0.1`; the default port
is selected by the OS. It accepts HTTP/1.1, one request per connection, with
`Connection: close` responses. There is no chunking, pipelining, forwarding to
`cardano-cli`, public-network endpoint or production authentication interface.

| Request | Representation |
| --- | --- |
| `POST /v1/transactions` | Original signed CBOR bytes; explicit `Content-Length` and exact `Content-Type: application/cbor` required |
| `GET /v1/transactions/<64 hexadecimal characters>` | Bounded pending, included or dropped status; unknown/expired history is `Unknown` |
| `GET /v1/state` | Complete pin, pool transaction/byte counts, eligible counts, revalidation and closed flags |

The transaction ID is Blake2b-256 of the **original body span**. A separate
SHA256 identifies the complete submitted envelope. Original body, witness,
validity and auxiliary spans are retained without reencoding. Structural
identity parsing alone does not validate signatures or ledger rules. An exact
duplicate of a currently retained entry is idempotent; a different envelope
with the same body ID cannot replace its original bytes. After chain movement,
expiry or inclusion, revalidation can change the result of resubmission.

Accepted and already-present responses contain a receipt with transaction ID,
envelope SHA256 and the complete admission pin. Every service response declares
`profileId`, `volatile: true` and `fullLedgerValidated: false`. Transport-level
errors contain a machine code and `fullLedgerValidated: false`; they do not
promise the complete service response schema. Original CBOR and keys are never
returned by the API.

The current ADA gate permits only supported coin-only vkey transfers. It runs
before the broader scoped transition validator. Native/Plutus scripts,
multiasset and minting, collateral/reference inputs, datum/reference scripts,
withdrawals, certificates, governance, auxiliary data and phase-two-invalid
transactions remain unsupported. Pending-parent inputs are not resolved from
the pool: inputs must exist in the pinned confirmed view. Passing this profile
does not confer full-ledger validity.

## Machine codes

| HTTP | Service code | Meaning |
| --- | --- | --- |
| 202 | `Accepted` | Validated for the restricted pinned view and reserved in the volatile pool |
| 200 | `AlreadyPresent` | Exact original already retained |
| 400 | `DecodeRejected`, `MalformedShape` | Malformed identity or supported ledger representation |
| 413 | `InputLimit`, `LocalLimit` | Original or scoped validator limit exceeded |
| 422 | `Unsupported`, `Rejected` | Outside profile, or failed a supported validation rule |
| 409 | `EnvelopeConflict`, `InputsReserved` | Same body with different envelope, or competing reserved input |
| 409 | `StaleState` | View changed; compare-and-reserve retry includes `currentPin` |
| 429 | `Capacity` | Pool count or byte limit reached |
| 503 | `Unavailable`, `InternalFailure` | Closed/unready/rebuilding owner or service, or sanitized internal failure |
| 200 | `State`, `Pending`, `Included`, `Dropped` | State/status query result |
| 404 | `Unknown` | No retained transaction status |

`Pending` has `status: pending` or `revalidating` and an explicit `eligible`
flag. Dropped reasons are `Expired`, `Removed`, `Shutdown`, `Conflict` or
`Validation`. Included status carries the publication pin and
`submittedEnvelopeByteEqualityVerified: false`: block body-ID matching does
not establish complete submitted-envelope equality.

Transport codes are `MalformedHttp`, `AmbiguousHeaders`, `UnsupportedFraming`,
`InvalidContentLength`, `UnsupportedRequest`, `TruncatedRequest` and
`InvalidTransactionId` (400); `ReadTimeout` (408); `ContentLengthRequired`
(411); `BodyTooLarge` (413); `ContentTypeRequired` (415); `HeadersTooLarge`
(431); `UnknownRoute` (404); `IngressUnavailable` (503); and
`InternalError`/`InvalidHandlerResponse` (500). Duplicate headers, missing Host,
Transfer-Encoding and Expect are refused. Socket-capacity overflow closes the
connection immediately; it does not guarantee a JSON response.

## Bounds and lifetime

| Resource | Current ceiling/default |
| --- | --- |
| Original transaction | 65,536 bytes |
| HTTP headers | 8,192 bytes |
| Accepted HTTP sockets | 8; nonqueuing acquisition before reading bytes |
| Service requests | 8, acquired before header/body reads and held through response write |
| Concurrent validation jobs | 2 shared across admission and revalidation |
| HTTP input/output | 5-second whole header/body read; separate 5-second response write |
| Pool | 64 transactions, 4,194,304 original bytes |
| Pending retention | 60 seconds from original admission, measured monotonically |
| Included/dropped history | 256 summaries, 60-second retention; no originals |
| Expiry maintenance | Once per second; reads and transitions also filter expiry |
| Rebuild scheduling | One worker, one latest-work slot and one advisory wake token |
| Relay leases | 2 globally per service; excess acquisition fails without queueing |
| One relay lease | At most 8 transactions, 524,288 original bytes, 30 seconds |
| Live scenario relay diagnostics | Last 128 event summaries; at most 4 session attempts |

Request resources release permits, not accepted entries. Cancellation before
compare-and-reserve leaves no admission; cancellation after that linearization
does not erase acceptance. Revalidation retains original admission time rather
than extending the 60-second lifetime. All state is volatile; restarting creates
a new owner and an empty pool. No pool recovery or durable submission receipt is
implemented.

## One mutation owner

`SubmissionOwner` exclusively owns a fresh coherent runtime. Its common gate
covers publication, rollback, anchor advancement, admission compare-and-reserve,
pool rebuild installation and eligibility capture. A pin contains the 32-byte
owner ID, uint64 generation, full hash/slot/block point, coherent-state ID,
ledger-state ID, environment ID, validation slot and exact profile ID. The
ledger view is checked against its ledger/environment/slot fields.

Preparation and validation run outside the gate. Publication compares the
**complete pin**, not merely a block height or generation. Successful chain
mutation increments generation, including rollback and anchor movement; a
rollback never restores an old generation. Exhaustion fails closed before wrap.
Failed chain operations do not increment generation. Unexpected trusted-callback
failure poisons the owner, refusing further mutation/admission; diagnostics do
not pretend to undo an already-published chain state.

The publication observer immediately makes survivors ineligible, removes body
IDs observed in the applied block and queues bounded revalidation. A latest-only
worker validates outside the gate and installs only against the same complete
pin and rebuild token. Rebuild results cannot resurrect entries removed or
expired while validation ran. Admission is unavailable while rebuilding.
Rollback removes orphaned inclusion summaries without resurrecting their
original transactions. Observer callbacks are bounded memory operations; they
must not reenter the owner, perform external I/O or wait for validation.

## Relay and inclusion

The service constructs exactly one `relaySource`. Lease creation selects and
installs eligible originals under the owner gate, so eligibility capture has
no gap before lease publication. A lease pins those exact bytes independently
of later pool movement. Its timer and resource finalizer release the registry's
originals; old lease handles cannot retrieve expired bytes or release a newer
lease's slot.

The [TxSubmission2 protocol implementation](txsubmission2.md) uses the pinned
Conway/NtN14 profile. Advertised size is the actual Conway GenTx encoding size,
including era and CBOR-in-CBOR wrapping, excluding reply-list/message/mux
framing. It differs from the original envelope's byte count. The codec accepts
the supported indefinite outer envelope while preserving body identity and
original reply bytes.

Announcements, body writes and acknowledgements are relay observations, not
evidence of remote acceptance or inclusion. Only an applied follower block
removes an included body ID. The test harness separately compares original
body and witness span digests; even those checks do not assert equality of the
complete submitted envelope with a reconstructed block transaction memo.

## Offline checks

The focused suites cover owner generation/fencing and failures
(`SubmissionOwnerSuite`), pure whitelist/pool transitions
(`AdaAdmissionPoolSuite`), request capability limits (`AdaIngressBudgetSuite`),
service concurrency/cancellation/rebuild races (`AdaSubmissionServiceSuite`),
lease lifetime/capacity/cleanup (`AdaRelaySourceSuite`), actual HTTP framing and
socket resource handling (`AdaHttpSuite`), representation and machine codes
(`AdaHttpHandlerSuite`), and client schema agreement (`AdaSubmissionClientSuite`).

`AdaAdmissionRelaySuite` constructs a valid signed ADA transfer with an
indefinite envelope and witness representation, admits it through the service,
leases its exact bytes and round-trips TxSubmission2. It checks body/witness
spans, actual encoded GenTx size, same-ID witness conflict, competing input
reservation and unchanged confirmed UTxO/fees/revision. This is offline
integration evidence; aggregate execution results are recorded separately.

## Private scenario invocation

`scripts/private_cluster_ada_submission.py` defaults to preflight only and
extends the [native controller execution contract](native-boundary-diagnostic.md#private-execution-contract).
Supply existing reviewed support/runtime pins and fresh private roots:

```sh
python3 scripts/private_cluster_ada_submission.py \
  --support-manifest <private-support-manifest.json> \
  --support-sha <manifest-sha256> \
  --owned-root <fresh-private-owned-root> \
  --evidence-root <fresh-private-evidence-root> \
  --scala-build-root <tested-build-root> \
  --scala-classpath-file <tested-classpath-file> \
  --projection <pinned-projection-binary> \
  --scala-image <pinned-image-id> \
  --java <java-executable>
```

Placeholders are not runnable credentials or sample manifests. Preflight checks
the actual pinned support closure; it does not generate a cluster or submit a
transaction. `--execute` explicitly enables the bounded isolated-local scenario.
The controller performs no reference CLI transaction submission.

The controller invokes Test-only `lab.AdaSubmissionMain` with
`INITIAL_DIRECTORY INITIAL_MANIFEST_SHA256 PORT MAGIC EXCHANGE_DIRECTORY`.
Here `PORT` is the owned reference peer port, not the API port. The API's
ephemeral loopback port is exchanged through `bootstrap-ready.json`.
The external Test-only client is `lab.AdaSubmissionClientMain` with
`apiPort transaction.cbor client-result.json`. Signed originals enter the Scala
service through HTTP; the client performs no reference CLI submission.

The finite scenario requires externally observed acceptance, Scala relay body
write, follower inclusion, removal from the pool, original body/witness digest
matching and exact held-state endpoint comparison across the local boundary.
It retains malformed-input and duplicate observations without treating a
post-movement duplicate as guaranteed `AlreadyPresent`.

Execution uses the existing private 1,000-slot/100ms, k=5 local profile, one
forger and one non-forging peer. The total ceiling is four CPUs/seven GiB:
reference two/three, Scala one/two and one serial helper or HTTP client one/two.
The reference has no external network; Scala/client share only its exact owned
namespace. No host ports are published. The controller bounds operation to
240 seconds plus 30 seconds cleanup, preserves failed evidence and verifies
owned-container removal. Raw captures, logs, keys, databases, manifests and
uncleared provider data stay outside Git.

## Later script profiles

Native scripts and Plutus must use this same ingress and explicit new profiles
when implemented. They require their own phase-one/phase-two checks, execution
budgets, collateral handling, datum/redeemer and reference-script rules, and
reference comparisons. Their current rejection must not be relaxed into a claim
that this ADA implementation already supports them.

## Reference initialization delay

The isolated reference profile explicitly sets `TxSubmissionInitDelay: 0`.
The pinned network implementation otherwise waits 60 seconds after protocol
initialization before requesting transaction IDs. That default exceeds this
publisher's five-second operation and thirty-second lease bounds; repeated
connections restart the delay. Two retained isolated attempts without the
override admitted through HTTP but did not reach an announcement or inclusion.

This is a supported node 11.1.3 setting, parsed by
[POM.hs](https://github.com/IntersectMBO/cardano-node/blob/11.1.3/cardano-node/src/Cardano/Node/Configuration/POM.hs#L387-L390)
and passed to diffusion by
[Run.hs](https://github.com/IntersectMBO/cardano-node/blob/11.1.3/cardano-node/src/Cardano/Node/Run.hs#L384).
The controller preserves the original configuration and records before/after
hashes and the sole changed field. It changes neither wire encoding nor ledger
rules. Success under this explicit local profile does not prove interoperability
with the reference default delay. Supporting that default requires a separately
bounded pre-lease startup design; the current lease limit is not extended.

## Acceptance record (2026-10-09)

The separate loopback HTTP client observed acceptance and inclusion. The Scala
relay wrote the original transaction through TxSubmission2, and the live
ChainSync/BlockFetch follower applied the containing block. The controller
compared the submitted and included body/witness span digests, verified removal
of the pool entry, and compared the held network-applied state against the native
endpoint acquired at the exact final point after crossing into epoch 1.

| Evidence | Value |
| --- | --- |
| Transaction ID | `07481c3025abce8ebb15891cf12d9d0aa6eafea61e2f9d3a3eab2bc9a2e7216a` |
| Inclusion | slot 211, block 2, `1eb27657bd5f87e3c0768aa3b3b1eb0196804e6dc15b12493e4d6d68e35e67d9` |
| Endpoint | slot 1020, block 29, `b3e41a5f35ef8b1e7e3e517aaf0385d5c24b0594665179524b75343ef158f049` |
| Source join ID | `e30f2fc1777ee97b44b77c5e740d8dbc4a96478e6cc27533be2c3d0f846cb7b9` |
| Held network-applied state ID | `f6e19d3561f52c425b552d9f5d0d7af869078813f6ab12a04adaa37edf66ad08` |
| Native endpoint acquisition ID | `d2082547ee97f030aed3b3b1f4c2acfbbb8c84ca0d438f8c35b16bc56e4c9fd7` |
| Included original-body SHA256 | `bfbafad339bdf306571a0552478fec9c22299594bbc8f2ddc7bc289cd3fc5e35` |
| Included original-witness SHA256 | `2f541890f8f6191c709c0236d4f28ea9c7fec2f1a5178dd90eceeb74a9d4e48c` |
| Scala result file SHA256 | `8343eb4f82af774cd66169aa6d5d2cc3501219db6b122794150c831a58815168` |

All 32 opened transports closed, resources finalized before success publication,
and the controller verified removal of the owned reference, Scala and client
containers. The run took 123.15 seconds. No public-network submission or real
funds were used. Full-envelope equality, full-ledger validation, native consensus
conformance and live pulser-cursor equality are not claimed; normalized governance
and represented protocol comparison passed within the documented profile.

Validation for the integrated source:

- 1,588 Scala tests passed across all nine modules; 25 public gates passed.
- 368 Python tests ran, with two skips (340 public controller/guard tests plus
  28 retained-data guard tests).
- The focused integration run passed 328 Scala tests. Its log SHA256 is
  `93836b4632b6d83eced154f4dff13b545c3270666d4799a71542987c699b1b9b`.
- Full public regression log SHA256:
  `2cf460098e05051dbb6fa97aca4d69ecaf0f98a61a2b5570d62464a43709394e`.
- Independent final source review found no blocking findings. The final
  controller SHA256 is
  `f42f584b6c8b0e4ae09fc456b75b369a9dd5dab82a46ba57f89784f33d54eae3`;
  its 21 focused Python guards passed and it was included in the full regression.

Private evidence is retained under `cardano-ada-live4-20261009`,
`ada4-20261009/exchange`, and `cardano-ada-public-20261009-retry1` outside Git.
The two default-delay failures and a third run interrupted during endpoint
acquisition are retained separately. Only the fourth complete run supplies this
acceptance record. Raw captures, keys, databases and logs are not published.

After the full regression, the three `AdaIngressBudgetSuite` tests were changed
to return MUnit-awaited futures instead of synchronous runners. All three passed
again with the formatting check; production code was unchanged. The focused
rerun log SHA256 is `685ea5fcacb5f663ef7758808058c14bd2a98f1d765d6cbc8a2f354f609a19c1`.
