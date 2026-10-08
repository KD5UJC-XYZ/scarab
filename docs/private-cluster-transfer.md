# Private reference transfer observation

This opt-in scenario extends the local research harness with one ADA-only transfer between disposable test addresses. It does not modify `RestrictedReplay` or its historical fixture profile. It does not establish complete Cardano validation, header signature validity, leader eligibility, or block production by Scala.

## Boundaries

`scripts/private_cluster_transfer.py` reuses the reviewed runner's local Linux-engine checks, pinned binaries, internal Docker network, resource/time budgets and owned-resource cleanup. Two reference producers and one nonproducing reference relay share only the isolated container namespace. Keys live only in the reference container's tmpfs; Scala receives public evidence only.

The official CLI does not expose arbitrary-point acquisition in the commands used here. Each pre/post observation therefore consists of separate CLI acquisitions under observed quiescence. Both owned producer processes are stopped and their stopped state is checked before and between the queries. Relay tip hash, slot, era and epoch must remain identical around all five queries. This is not an atomic snapshot or a LocalStateQuery acquired-state implementation. Unexpected tip/epoch/parameter transitions fail the scenario.

The driver captures full UTxO JSON and CBOR, ledger state, protocol state, protocol parameters, genesis, original signed transaction CBOR and tip/process brackets. The ledger-state JSON export has an empty UTxO field in this run; full UTxO evidence comes from the separate `query utxo --whole-utxo` acquisitions, not that empty field. The actual reference fee-pot field is `stateBefore.esLState.utxoState.fees`; its before/after difference is recorded separately from the transaction's declared fee.

The context is explicitly caller-attributed: the Python adapter extracts numeric fields from the retained official JSON exports. Scala checks the source-file SHA-256 attribution and a bounded strict context manifest, but does not independently parse those JSON exports to derive the manifest's numeric projection. This trust boundary applies to all externally supplied context values; the source digests are attribution, not proof of their semantic projection.

## Checked scope

The new `conway-pv9-cluster-ada-transition-v1` context requires ledger protocol 9.0, same-epoch advancing points, explicit fee/size parameters and nondecreasing fee-pot observations. Header protocol version is separate (11.2 was observed).

Scala uses its real handshake, ChainSync and BlockFetch implementation against the reference relay to capture at most eight blocks from the pre-point through the post-point. It compares original header bytes/hash, predecessor, slot/block number and body commitment. The closed range must contain exactly one transaction, with original body and witness bytes identical to the submitted transaction. Invalid transaction indices and auxiliary data are unsupported and rejected.

The pure transition comparison checks selected input resolution, ADA/testnet outputs, required key coverage, experimental public witness signature verification, value conservation, minimum fee and maximum size. It requires the complete observed UTxO key delta, identical untouched output bytes, matching created output values/addresses and an actual observed fee-pot delta equal to the declared fee. Unsupported selected inputs or transaction features fail. This is still a restricted predicate, not all ledger rules (for example, not a complete minimum-UTxO rule implementation).

## Submission scope and evidence

The first transfer attempt stopped before submission because the CLI defaults transaction-ID output to JSON; the adapter now selects documented `--output-text` and retains the receipt. The second attempt was accepted into the relay mempool but was not observed in a block within the bounded inclusion window, while producers continued forging. Relay transaction propagation remains unresolved.

The successful third attempt also submitted the same signed transaction directly to an owned producer after the baseline queries and producer resume. It proves reference inclusion and the restricted Scala comparison, not relay-to-producer transaction propagation. Original failed-attempt evidence is retained outside the repository.

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

`ClusterTransferEvidenceSuite` registers twelve opt-in tests when `CLUSTER_TRANSFER_EVIDENCE` names a retained successful public-evidence directory visible inside the test container. It checks original acceptance and rejected context, fee-pot, state, signature, truncation and inclusion mutations. Run affected ledger/app/network-runtime tests in the pinned JDK container with that evidence directory mounted read-only. `scripts/test_private_cluster_transfer.py` tests observation guards without making interoperability claims. No live evidence, keys or cluster database is added to Git.
