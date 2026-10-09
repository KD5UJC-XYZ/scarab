# Bounded offline native-source boundary diagnostic

This private integration consumes the checked v2 packet without rewriting original
request, capture, protocol, epoch, whole-UTxO or seed bytes. It prepares a supplied
diagnostic context and reuses the existing scoped one-boundary coordinator. It does
not introduce a production CLI, native seed admission, durable import, full ledger
validation or consensus conformance.

## Source contracts

`SequenceInput.fromNativeDiagnostic` requires the expected checked join identity,
epoch zero, a point before the stability window, exact native reward Absent and
compatible crossing geometry. A distinct context identity binds the join. Explicit
original UTxO, stake-source identity and protocol-attribution fields replace legacy
filename assumptions inside the shared coordinator. The legacy constructor retains
its original identity recipe. Native inputs are never fabricated into legacy JSON
or hex-text exports.

Ordinary runtime, ordinary stake and legacy synthetic-reward factories reject the
diagnostic context. Durable creation rejects before opening a store. Only the
internal supplied one-boundary path permits this context; its existing complete
component checks still run. Local checkpoint and synthetic recovery exports remain
unsupported for that composition.

`NativeLinkedBlocks.bind` checks independently pinned original disk-era blocks,
full anchor and terminal points, strictly increasing slots, consecutive block
numbers, parent hashes and body commitments. It uses existing block indexing and
decoding. The header byte span is original; the NtN envelope around it is explicitly
derived and is not captured wire evidence. Limits are 128 blocks, one MiB per block,
64 MiB total, an epoch-zero anchor and epoch-one terminal. This structural binding
alone does not check signatures or confer admission.

The private extraction receipt binds each block to its exact offset and length in
a hash-pinned, stopped database file. Extraction copies spans unchanged and does
not start a node or modify retained databases. Database retention alone is not an
exported trace. The subsequent Scala run performs the coordinator's supported
header/body and supplied-state checks over that exported trace.

## Opt-in execution contract

The ten-file input directory and independently pinned `adapter-inputs.json` use the
contract in [native ledger seed](native-ledger-seed.md). The crossing suite also
requires `NATIVE_LINKED_BLOCKS_BUNDLE` and
`NATIVE_LINKED_BLOCKS_MANIFEST_SHA256`. Its exact manifest fields are:

```json
{
  "schema": "native-linked-originals-v1",
  "sourceJoinId": "<checked v2 join SHA256>",
  "databaseInventorySHA256": "<retained inventory SHA256>",
  "anchor": {"hash": "<header hash>", "slot": 184, "blockNo": 2},
  "terminal": {"hash": "<header hash>", "slot": 1045, "blockNo": 56},
  "blocks": [{"file": "block-0000.cbor", "sha256": "<SHA256>", "bytes": 856}]
}
```

The example describes the contract; it is not a usable manifest. Block filenames
are consecutive fixed ordinals. The inventory hash is attribution, not proof of
database validity. Independently reviewed extraction receipts retain the original
file/offset provenance. All captures, manifests, database files and native helper
binaries remain private and outside Git.

## Verified first crossing

The fresh isolated capture anchored at slot 184/block 2 and the extracted stream
ended at slot 1045/block 56. The explicitly enabled Scala test accepted all 54
original successors, applied one scoped epoch boundary and reached the exact
captured terminal hash. There were 46 receipt compactions and eight retained blocks.
No second cluster or native build was needed: the existing pinned projection
binary ran once offline on the new seed, and extraction read the retained database.

The final offline run passed **181 Scala tests** (156 app, 25 ledger), including
the actual fresh-v2 join and original-block crossing, nine generated linked-block
tests, five new diagnostic provenance/refusal tests, and existing coordinator and
ledger regressions. Formatting, owned-container cleanup and before/after input
hash checks passed. Resource limits were two CPUs/two GiB with no network and a
private build/cache. Earlier test syntax/helper failures are retained; no production
guard was relaxed. A host post-run variable-name collision was corrected and all
input hashes independently rechecked after the successful Scala exit.

Test log SHA256:
`d5a3d629f161e2bd8c86161612b4bb9e35b65b4952d1e60c5baeaa568f4d8851`.

That first checkpoint established bounded supplied-state replay; it had no native
endpoint comparison. The separate endpoint check below extends the evidence while
retaining the same runtime restrictions.

## Same-point endpoint comparison

The separate v2 acquisition uses the exact terminal point at epoch 1, slot 1045,
block 56, hash
`ef522fc8e82c4daaafb07e6e66a89e2fa4dd09e45dd3b3f069b95b0a4888288b`.
Its original eight-file packet is externally hash-pinned; the effective genesis
hash must equal the initial checked join. The opt-in test requires
`NATIVE_ENDPOINT_BUNDLE`, `NATIVE_ENDPOINT_MANIFEST_SHA256` and
`NATIVE_ENDPOINT_ACQUISITION_RESULT`. The endpoint manifest contains exactly
`schema`, `point`, `genesisSHA256`, `acquisitionResultSHA256` and `inputs`;
its schema is `native-endpoint-reviewed-inputs-v1`. Each input declares exact
byte count and SHA256. The retained acquisition-result bytes are also hash-checked.
This is source attribution, not cryptographic snapshot authentication.

Three test-only comparators consume the original endpoint bytes. They do not
reuse or relax epoch-zero seed admission guards. The checked ledger report has a
private constructor and binds the exact replay-state ID before governance comparison.

| Domain | Endpoint check |
| --- | --- |
| Praos payload | Exact last slot, all six nonce fields, complete certificate-counter map; unknown and Neutral remain distinct |
| Ledger and stake | Whole coin-only UTxO addresses/values, instantaneous stake, accounts, pools, all mark/set/go metadata and snapshot fees |
| Accounting | Previous/current issuer counts, treasury, reserves, fees, deposits and donations |
| Boundary state | Exact Absent reward encoding, absent replay pulser/freeze, empty nonmyopic likelihoods and reward pot, leadership fractions/stakes/VRFs |
| Historical fields | Bounded shapes and unchanged original bytes for genesis delegations and VRF multiplicity; these are preservation checks, not replay-computed indexes |
| Parameters | Exact installed post-boundary current/previous bytes, plus the two exact parameter spans in normalized enactment |
| Governance | Accounts including votes, registrations/delegators, dormancy, committee, constitution, empty proposals/roots, future-parameter tag, completed DRep/pool distributions and empty ratification/enactment |

The native DRep query serializer finishes its pulser. The test therefore compares
normalized completed serialization for the empty-proposal profile against a fold
of Scala's fresh pulser inputs. It does not recover or compare the native live
cursor. Registered DRep credentials accumulate instantaneous stake plus rewards;
the native fold does not filter registration by expiry. Map/set ordering is
normalized with duplicate rejection; parameter original spans remain exact.
The source pins are Conway 1.23.0.0 `DRepPulser.hs`
`c0e5c984d28c69ff024e9f3d950284c7fd122ff22703279b744141d9720fcf97`,
core 1.21.0.0 `State/Governance.hs`
`e836e80dabae53177e4f4aa8e7f5fb51b3e5b3a6276d0ba98d11fadc327ebcae`,
and binary 1.9.1.0 `Encoding/Encoder.hs`
`3c6f222efddd43995afa425a18a748d35ca8f629799d5d2a362629db4bd50b47`.

Earlier failed attempts remain in private evidence. A generated test helper first
failed compilation; a second negative test incorrectly swapped equal rolled-over
parameters. Actual comparison exposed two comparator assumptions: genesis
delegations were nonempty but unchanged, and initial parameter provenance roles
were used where installed post-boundary roles were required. The corrected checks
preserve exact original-byte equality. No production transition was modified to
make the endpoint comparison pass.

The final offline run passed **202 Scala tests** (177 app,
25 ledger), including the actual original-block replay and exact
endpoint comparison. Formatting, source/input hash checks and owned-container
cleanup passed under two CPUs/two GiB, private caches and no network.
Test log SHA256: `c7c53898f6952561eef23d6e906d7025ec8943c6a8a2830f56c41b62d07116d0`.

This is equality of the represented finite profile after one boundary. It is not
full ledger or consensus validation, productive nonempty-go reward validation,
live streaming, persistence, second-boundary support, or snapshot authentication.
Nonempty governance actions and original live pulser cursor equality remain
unsupported. Public runtime admission remains disabled. The next live milestone
requires real ChainSync/BlockFetch input while the cluster crosses the boundary,
followed by a separately acquired same-point endpoint comparison.
