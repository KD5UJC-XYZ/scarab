# Private reference transfer observation

This opt-in scenario extends the local research harness with one ADA-only transfer between disposable test addresses. It does not modify `RestrictedReplay` or its historical fixture profile. It does not establish complete Cardano validation, header signature validity, leader eligibility, or block production by Scala.

## Boundaries

`scripts/private_cluster_transfer.py` reuses the reviewed runner's local Linux-engine checks, pinned binaries, internal Docker network, resource/time budgets and owned-resource cleanup. Two reference producers and one nonproducing reference relay share only the isolated container namespace. Keys live only in the reference container's tmpfs; Scala receives public evidence only.

The official CLI does not expose arbitrary-point acquisition in the commands used here. Each pre/post observation therefore consists of separate CLI acquisitions under observed quiescence. Both owned producer processes are stopped and their stopped state is checked before and between the queries. Relay tip hash, slot, era and epoch must remain identical around all five queries. This is not an atomic snapshot or a LocalStateQuery acquired-state implementation. Unexpected tip/epoch/parameter transitions fail the scenario.

The driver captures full UTxO JSON and CBOR, ledger state, protocol state, protocol parameters, genesis, original signed transaction CBOR and tip/process brackets. The ledger-state JSON export has an empty UTxO field in this run; full UTxO evidence comes from the separate `query utxo --whole-utxo` acquisitions, not that empty field. The actual reference fee-pot field is `stateBefore.esLState.utxoState.fees`; its before/after difference is recorded separately from the transaction's declared fee.

The v2 context manifest (`conway-pv9-cluster-context-v2`) hashes the exact original genesis, pre/post parameter exports, pre/post tip arrays and pre/post ledger-state exports. Scala parses those original bytes, requires unchanged same-point tip brackets, and compares every numeric manifest field with its source: network magic, ledger protocol version, fee/size parameters, point slots/epochs and actual fee-pot fields. Ledger epoch must agree with its tip bracket. The manifest's point hashes must match the original tip JSON. These are semantic projection checks in addition to byte attribution; they do not authenticate an external exporter or turn separate acquisitions into an atomic snapshot.

`ReferenceJson` is a small bounded reader in this app with no extra dependency. It rejects invalid UTF-8, duplicate decoded field names at any depth, invalid JSON grammar, excessive bytes/nesting/nodes/strings/numeric lexemes and unpaired surrogates. Projected numerics must be unsigned decimal integer tokens (no signs, decimal points, exponents or strings), at most uint64; narrower network/protocol limits still pass through checked construction. Original reference JSON contains fractional values outside the projected fields, so valid JSON fractions elsewhere are retained as lexical tokens rather than floating point. The existing ledger predicate profile and legacy profiles remain unchanged.

The earlier reviewed live3 receipt used the v1 caller-projected manifest. Offline v2 tests use a separately attributed migrated manifest over unchanged original export bytes, stored outside Git; this is not a retroactive new live run.

## Checked scope

The new `conway-pv9-cluster-ada-transition-v1` context requires ledger protocol 9.0, same-epoch advancing points, explicit fee/size parameters and nondecreasing fee-pot observations. Header protocol version is separate (11.2 was observed).

Scala uses its real handshake, ChainSync and BlockFetch implementation against the reference relay to capture at most eight blocks from the pre-point through the post-point. It compares original header bytes/hash, predecessor, slot/block number and body commitment. The closed range must contain exactly one transaction, with original body and witness bytes identical to the submitted transaction. Invalid transaction indices and auxiliary data are unsupported and rejected.

The pure transition comparison checks selected input resolution, ADA/testnet outputs, required key coverage, experimental public witness signature verification, value conservation, minimum fee and maximum size. It requires the complete observed UTxO key delta, identical untouched output bytes, matching created output values/addresses and an actual observed fee-pot delta equal to the declared fee. Unsupported selected inputs or transaction features fail. This is still a restricted predicate, not all ledger rules (for example, not a complete minimum-UTxO rule implementation).

## Submission scope and evidence

The first transfer attempt stopped before submission because the CLI defaults transaction-ID output to JSON; the adapter now selects documented `--output-text` and retains the receipt. The second attempt was accepted into the relay mempool but was not observed in a block within the bounded inclusion window, while producers continued forging. That run ended before the configured 60-second inbound transaction-request delay elapsed; it did not establish a lasting diffusion failure.

The successful third transfer attempt also submitted the same signed transaction directly to an owned producer after the baseline queries and producer resume. It proves reference inclusion and the restricted Scala comparison, not relay-to-producer transaction propagation. Original failed-attempt evidence is retained outside the repository.

dev-host evidence: `/home/<user>/cardano-transfer-live3-20261008`.
Transaction: `1f25ff502de89f7629ffa75c9a77001261efa00554e3e5906cd3f514785041ef`.
Pre-point: slot 127, `48df2c2b2c132cf6b38bc39ccd08003e6b76d8630daf695e25e92963ad9ab6d2`.
Post-point: slot 199, `77de9de55a789d8914a92d2bd6770c414c09e27885cb0e43f94cd63b0703295c`.
The reference included a 10 ADA transfer plus change in one block; seven original UTxO entries were untouched. The observed fee pot changed from 0 to 200000 lovelace.

## Reproduction

Prepare the verified local image using the existing reference-image workflow and compile the application runtime classpath. Then run from the WSL repository:

```sh
python3 scripts/private_cluster_transfer.py --reference-image cardano-reference-11.1.3:local --output /home/<user>/cardano-transfer-UNIQUE --scala-repo <repo> --seconds 300
```

The output directory must be fresh. Do not use a remote engine, public peers, host networking, published ports, real keys or funds. The runner cleans only its uniquely named resources.

`ClusterTransferEvidenceSuite` registers opt-in tests when `CLUSTER_TRANSFER_EVIDENCE` names a retained successful public-evidence directory visible inside the test container. It checks original acceptance and rejected context, fee-pot, state, signature, truncation and inclusion mutations. Run affected ledger/app/network-runtime tests in the pinned JDK container with that evidence directory mounted read-only. `scripts/test_private_cluster_transfer.py` tests observation guards without making interoperability claims. No live evidence, keys or cluster database is added to Git.

## Relay-only diagnostic

`scripts/private_cluster_relay.py` extends the same runner without direct producer submission or fallback. It preserves the observed 60-second transaction-request startup delay. Before submitting, all three nodes must show both expected loopback peers hot, full-duplex connection counters, at least 65 seconds since the latest hot transition, and actual transaction-ID request trace activity. It then waits for an early epoch window, captures the same quiescent baseline, and submits only through the relay socket. Local stdout tracing is narrowed to transaction and connection evidence; no external tracing destination is introduced. The command records original configuration, connection/request observations for each expected peer endpoint, submission timestamps, mempool admissions and inclusion polling timestamps. Each tip query is now saved verbatim; the hashed bracket array frames these unchanged original JSON payloads instead of re-encoding their numbers. The first relay-only run preceded that raw-tip receipt improvement and is retained with its original normalized tip arrays. A 420-second workload budget and the inherited 600-second overall bound include cleanup. A fixed sleep alone is never counted as readiness.

```sh
python3 scripts/private_cluster_relay.py --reference-image cardano-reference-11.1.3:local --output /home/<user>/cardano-relay-UNIQUE --scala-repo <repo> --seconds 420
```

The first relay-only diagnostic (`/home/<user>/cardano-relay-live1-20261008`) succeeded without producer fallback. First outbound transaction-ID requests occurred about 60.064 seconds after initial hot-peer promotion on each node. The relay admitted transaction `dd9d88f879eb23dbc0887c483b82c9060efc65f9ffcda063ed22fed4f518091e` at 20:01:54.528 UTC; producers admitted it at 20:01:54.676 and 20:01:54.821. Inclusion was first observed 1.709 seconds after CLI completion. Scala checked one actual block between slots 1054 and 1130, epoch 2, with a 200000-lovelace observed fee-pot increase. The run completed in 227.373 seconds and cleanup was verified. These are local disposable-reference observations, not production network timing guarantees.

The final confirmation (`/home/<user>/cardano-relay-live2-20261008`) used the finalized verbatim-tip capture and per-peer request checks. Transaction `19e4af0f88316ac82fb3d7a93629f4cc2afec8a07836bcd4d272d9d7ab2938bd` was admitted by the relay at 20:06:57.021 UTC and by producers at 20:06:57.205 and 20:06:57.368. Inclusion was first observed 0.943 seconds after CLI completion. Scala matched the one-block transition from slot 1014 to 1078, epoch 2, including the actual fee-pot change 0 to 200000. All sixteen original tip payloads were preserved byte-for-byte. The run completed in 243.729 seconds, advanced from epoch 2 to 4, and removed every task-owned container/network. Validation for this packet: 295 affected Scala tests (including 26 retained-evidence tests and six JSON-reader tests) and 21 Python guards passed.
