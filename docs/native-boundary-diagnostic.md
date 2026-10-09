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

This establishes one bounded supplied-state diagnostic replay. It does not compare
the resulting full state against a native endpoint-state packet. Source
authentication, full parameter/cost-model validity, original live governance cursor
recovery and general ledger/consensus conformance remain unproved. A same-point
native endpoint epoch/UTxO/protocol packet is still needed for a separate native
post-state comparison. Second boundaries, nonempty-go likelihood generation and
persistence remain unsupported by this profile. Nothing was published or enabled
as a production runtime.
