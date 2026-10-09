# Sustained exact-point native oracle

Additive reuse of the reviewed epoch-query-client. PROVENANCE.json pins the original files; other worker directories and dependency stores remain unchanged.

The capture executes one SpecificPoint acquire at caller-supplied slot/hash with exactly the requested NTC version (16 for this fixture). It queries acquired point/block, Conway GetCBOR DebugNewEpochState, GetUTxOWhole, DebugChainDepState, GetCurrentPParams, then point/block again. DebugChainDepState comes from the acquired ExtLedger header state. No tip query, fallback, reacquire or retry exists. Point-too-old/not-on-chain is terminal. Success requires Release continuation and successful connection return; Release has no acknowledgement.

Original payloads are retained as original-debug-epoch.cbor, original-whole-utxo.cbor, original-protocol.cbor and original-parameters.cbor. Native full-consumption decoding, semantic roundtrips and 4096-entry UTxO cap run before output. Derived-ledger JSON contains lastEpoch/stateBefore/stakeDistrib/blocksBefore/blocksCurrent; it is the supported projection, not the full CLI JSON representation. Derived-protocol includes lastSlot, oCertCounters and six nonce fields. Derived parameters and UTxO use native JSON encoders. All derived JSON bytes travel as hex strings and are written unchanged, preserving native decimal encodings. They are never labelled original CLI exports. No full-ledger validation, monetary parity or reward-seed admission is claimed.

## Invocation

python3 /client/packet.py --query-helper /helper --query-sha256 SHA256 --request /request.json --output /output/new-packet

Request has exactly schema=1, socket, point{slot,hash}, networkMagic, byronEpochSlots, ntcVersion, producerBinarySHA256 and producerImage. Producer identity fields are caller pins, not remote attestations. Source/compiled/native-library pins require independent review before use.

Files additionally include request.json, capture.json, derived-ledger.json, derived-utxo.json, derived-parameters.json, derived-protocol.json, and receipt.json written last. Receipt contains point/blockNo, queryHelperSHA256, every other fileSHA256, utxoEntries, acquireCount=1/reacquireCount=0, release=sent-no-ack; runtimeImport, monetaryParity, rewardSeedAdmission and fullLedgerValidation are false. admissionChecks=not-performed.

Acceptance requires public wrapper exit zero, independently verified receipt/file identities, and confirmed owned-container cleanup. A receipt alone does not establish success: timeout or cancellation may occur after its rename and before wrapper completion. Failed or ambiguous output is retained as evidence but not accepted. No automatic retry.

## Resource and lifecycle boundary

Mandatory runtime container: existing digest-pinned image, network none, read-only source/helper/native libraries, only authorized shared Unix socket, private writable output, 1 CPU/1 GiB/no extra swap, bounded PIDs/tmp. Public wrapper has one 20-second supervised budget covering parsing, sealed-memfd helper, native verification and publication. Helper inherits the worker process group. SIGTERM/SIGINT trigger cleanup, including descendants; process wait is bounded to two additional seconds. The controller must use ONE absolute 25-second deadline from container operation start and ownership-verified Docker cleanup with only the remaining allowance, never add a fresh five seconds after 22. Unconfirmed cleanup is fatal. SIGKILL/host loss requires the external container owner. Do not call collect directly for live use.

RTS: -M768m -K16m -N1. Each native payload at most8MiB, helper256MiB, stdout64MiB/stderr128KiB. Native payload/map limits apply after decoding, not before allocation; memory/time boundaries are mandatory. The original DebugNewEpochState empty UTxO convention is checked before replacing it only for native verification; no derived seed is exported/admitted.

Runtime image sha256:29cdc34ede8cd9716d1f40a8200447355ae210c05a539c1b0262ccc5fcaf183c, Python3. Native libsodium.so.23/libblst.so/libsecp256k1.so.2 must be pinned and mounted read-only with LD_LIBRARY_PATH. No warm dependency store or GHC is required at runtime. Tests used synthetic Unix peers only; no live Cardano capture or timing guarantee follows.

## Checks

The typed harness exercises one acquire, refusals, era/point/block errors, all four reply caps, native malformed/trailing input, 4096/4097 UTxO boundary, immediate public-tip advancement and cancellation. Encoded tests exercise actual Transport/mux, exact native queries and original four payloads, nonneutral nonce/counter JSON shape, required parameter/fee fields, exact derived byte publication and public-wrapper cancellation. Native runtime six groups passed at 1CPU/1GiB; private compile closure contains exactly312 unchanged external units. See CONFORMANCE.json for pinned evidence and limits.

## Retained nonempty stake compatibility

The separately bounded incremental gate adds offline project-retained. It decodes retained original epoch/whole-UTxO CBOR through the same native projection and compares supported ledger fields with retained CLI JSON. Full stake distribution equality passed for two nonempty pools, including exact pool hashes, VRF hashes, rational fractions2/3 and1/3, stake totals, epoch and fees. Six UTxO entries were decoded. The test does not manufacture a new acquisition: its protocol and parameters are synthetic placeholders and are explicitly not compared. The retained evidence remains private and is referenced only by digest. The six encoded/process regression groups also passed after this harness-only change.

The earlier gate was observed at938.12seconds against900seconds after idle freeze/report time; no further build/test ran under that expired allowance. The incremental gate has a distinct300-second allowance and preserves the original budget file unchanged.
