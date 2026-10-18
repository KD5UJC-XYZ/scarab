# Ledger snapshots in the Haskell node's directory layout (Step D)

**Status**: Accepted (owner approved D1-D9, 2026-10-18). Stage S1 (the MemPack `TxOut` codec, [#143](https://github.com/KD5UJC-XYZ/scarab/issues/143))
is built and measured (section 8: the codec is checked against the 4,526,058 outputs of a real preprod table and 1,731,810 of this node's own stored outputs, and is not adopted as the stored value); S2 is implemented and reference-checked (section 9); S3 import is implemented (section 10); S4 export is implemented and reference-byte-checked (section 11); S5 is reference-checked on six private epoch boundaries, preprod epoch 320 and the captured point reported by an existing synced preprod node (section 12). Tracked in
[#140](https://github.com/KD5UJC-XYZ/scarab/issues/140) (roadmap E2, D16); the first consumer is the Mithril import
([#27](https://github.com/KD5UJC-XYZ/scarab/issues/27), E1), then mainnet from a Mithril snapshot
([#69](https://github.com/KD5UJC-XYZ/scarab/issues/69), D9). Part of "Step D" of
[ledger-mirror step 2](ledger-mirror-step2.md) ([ADR 0009](../adr/0009-ledger-mirror-step-2.md)).

Every claim about the reference carries its file and line, read at the revisions that `cardano-node` 11.1.3 pins
(`PINS.txt` of the local upstream copy: ouroboros-consensus 4.2.1.0 at 82ecba3, cardano-ledger-core 1.21.0.0,
conway 1.23.0.0, babbage 1.14.0.0 at f649f97, cardano-ledger-binary 1.9.x). Two things are not pinned there and are
named where they matter: the `mempack` package (0.2.1.0 was read; its changelog has no wire-format change between 0.2.0.0
and 0.2.1.0, and the node's solver can only pick one of those two, since 0.2.0.0 is the lower bound of
`cardano-ledger-core`) and `cardano-crypto-class` (2.6.0.0 was read for the `Hash` and `PackedBytes` `MemPack`
instances; the format is confirmed byte for byte on real files below). Mithril is not pinned by the node: its repository was read at `main` 769d862 (mithril-client 0.14.33, mithril-aggregator 0.10.20, cloned 2026-10-15), and what it says of the ancillary archive was checked against the real archive of 2026-10-17 (section 3.1). Paths below are relative to the root of the named
repository; `OC` is `ouroboros-consensus/src/ouroboros-consensus/Ouroboros/Consensus`, `OCC` is
`ouroboros-consensus-cardano/src`, `CL` is `cardano-ledger`.

## 0. Summary and accepted decisions

The owner approved all nine recommendations below on 2026-10-18. They govern S2-S5: keep V5 and
the stored CBOR values, and convert to the reference snapshot layout and MemPack at import/export
boundaries. The initial backend, offline operation and Byron restrictions are explicit scope limits;
consensus and ledger validation do not change.

What a Haskell ledger snapshot is, in one paragraph: a directory `ledger/<slot>[_<suffix>]/` of three files. `state` is
the `ExtLedgerState` without the UTxO (CBOR, `[1, ...]`), which Scarab already reads and writes byte for byte (it is the
"Haskell section" of snapshot V5). `tables` is the UTxO: `[ {txid ++ be16 index -> MemPack TxOut} ]` as CBOR byte strings,
sorted, 1 GB for the preprod tip. `meta` is a three-field JSON whose `checksum` is the CRC32 of the decimal strings of the
CRC32 of `state` and of `tables`. The MemPack `TxOut` is the missing piece (S1). Everything else is streaming and plumbing.
A Mithril cardano-database download with `--include-ancillary` contains exactly one such directory, written by a node.

Two facts found while writing this, which change earlier statements:

* **The `meta` checksum is not the CRC32 of the two files together** (the fixture README says it is the checksum of `tables`, the comment of `SnapshotMeta` that it is of the two files together; the step 2 document has the rule right).
  It is `CRC32( decimal(CRC32(state)) ++ decimal(CRC32(tables)) )` (`OC/Util/CRC.hs:20-26`, used at
  `OC/Storage/LedgerDB/V2/InMemory.hs:223`). Checked on a Mithril preprod directory and a devnet fixture (section 2.3).
* **The preprod `state` of a Mithril snapshot already round-trips through `HaskellLedgerFile`**: the 32,511,416-byte file of a
  node 11.1.3 snapshot (Conway, slot 136,511,997, block 5,300,868) decodes in 1 s and writes back identical. No preprod or
  mainnet state had been read before ([compatibility.md](../compatibility.md)). That removes the largest risk of S3.

| # | Decision | Recommendation |
|---|---|---|
| D1 | Which backends | The Mem backend (`utxohd-mem`) for import and export. It is what Mithril ships and what node 11.1.3 reads with its default `V2InMemory`. An LSM snapshot is converted by the operator with `snapshot-converter` (section 2.2); Scarab never reads LSM run files. |
| D2 | Does V5 stay | Yes. V5 is the per-boundary fast snapshot (sidecar, UTxO as a RocksDB checkpoint, hard-linked, no 1 GB stream). Step D adds `export-ledger-snapshot` and `import-ledger-snapshot` beside it; the node does not write Haskell-layout directories as it runs. A periodic export can be an opt-in flag later. |
| D3 | The stored UTxO value as MemPack | **Do not adopt it now** (section 8.5): on preprod it is the same size as the stored CBOR value (231.6 against 230.6 bytes an output) and decoding to the node's `TxOut` costs the same (2.66 against 2.55 microseconds), because the original encoding the rules re-read has to be rebuilt. S2 to S5 convert per output at the boundary, about 5 microseconds each way. Revisit when `TxOut` no longer needs `raw` or a mainnet measurement shows a size saving. |
| D4 | Trust in the ledger part of a Mithril snapshot | The certificate covers the immutable files only. The ledger directory is in the ancillary archive, signed by one Ed25519 key over a manifest of SHA-256 digests (section 3.3). Import verifies that manifest signature with the network's ancillary key (given by the operator) and the file digests, refuses without it unless `--no-verify-ancillary` names the risk, and records in the evidence which of the two was used. |
| D5 | Framing of `tables` on export | The node's own rule: a definite map head for 23 entries or fewer, an indefinite one above (sections 1.2 and 2.2). The reader takes both. `snapshot-converter` always writes an indefinite map; both are accepted by node and converter, so byte-identity with a node-written file is a goal of the test, not a requirement of the format. |
| D6 | Export while the node runs | No. `export-ledger-snapshot --db <dir>` reads a database directory (copy it if the node runs), from the newest V5 snapshot and its UTxO checkpoint. A snapshot is always at the immutable tip, so nothing is in flight. |
| D7 | Byron | Out of S3 and S4. A Byron-era snapshot needs the `ChainValidationState` codec (the mirror keeps `ByronItem` raw); a Mithril or mainnet snapshot is never in Byron. Import and export refuse a Byron tip with a typed error; the codec is a separate stage if the owner wants db-analyser snapshots of Byron blocks. |
| D8 | Directory suffix on export | `<slot>_scarab`. A suffix makes the node's snapshot policy keep the directory (`OC/Storage/LedgerDB/Snapshots.hs:296-297`, `defaultDeleteSnapshotIfTemporary`), so a node started on an exported directory does not delete it. |
| D9 | The Mithril acceptance network | Preprod (1 GB `tables`, 4.5M outputs, one 440 MB download). Preview is smaller still; mainnet is the goal of D9/#69 and is not needed to accept the format. |

## 1. What the Haskell node writes and reads

### 1.1 Where snapshots are

The LedgerDB of cardano-node 11.1.3 has two backends, both "V2": in memory and LSM (`cardano-node/src/Cardano/Node/Configuration/LedgerDB.hs:35-42`; the
configuration key `LedgerDB.Backend`, default `V2InMemory`, `Configuration/POM.hs:545-551`). The V1 backing stores (LMDB) are
gone from this version: the Mithril manual says so for node 11.1.0 and up (`mithril/docs/website/root/manual/getting-started/bootstrap-cardano-node.md:547`).
The snapshot directory is `<db>/ledger/` and a snapshot is a directory named by its slot and an optional suffix:
`snapshotToDirName` (`OC/Storage/LedgerDB/Snapshots.hs:432-437`), parsed by `snapshotFromPath` (299-309: the prefix before the
first `_` must read as a number, the rest is the suffix). A *file* where a directory is expected is a pre-UTxO-HD snapshot and is refused
(`ReadSnapshotIsLegacy`, `V2/InMemory.hs:244-245`). Snapshots with a suffix are never deleted by the policy (`diskSnapshotIsTemporary`,
`Snapshots.hs:296-297`). The appendix of `ouroboros-consensus/docs/website/contents/references/consensus_configuration.md:368-398` shows the layout.

### 1.2 The three files (Mem backend)

`implTakeSnapshot` (`V2/InMemory.hs:190-224`) creates the directory, then:

1. `state`: `writeExtLedgerState` (`Snapshots.hs:395-408`) writes `encodeL` of the `ExtLedgerState`: `[1, <ExtLedgerState>]`, version number 1
   (`snapshotEncodingVersion1`, 457-463). The reader also accepts the old format of version 3 with a tip and a chain length in front
   (`decodeLBackwardsCompatible`, 480-500). The CRC32 is of the bytes written (`hPutAllCRC`).
2. `tables`: `implTakeHandleSnapshot` (`V2/InMemory.hs:154-170`) writes `valuesMKEncoder` (`OC/Ledger/Tables.hs:276-283`): a CBOR array of length 1
   (`encodeListLen 1`) holding `encodeTablesWithHint`, which for a Shelley-based era is `encodeMap encodeMemPack encodeMemPack tbs`
   (`OCC/shelley/Ouroboros/Consensus/Shelley/Ledger/Ledger.hs:373-375`). For the Cardano block the encoder is chosen by the **tip era of the telescope in `state`**:
   Byron an empty map (`encodeMapLen 0`), the others the era's map (`OCC/ouroboros-consensus-cardano/Ouroboros/Consensus/Cardano/Ledger.hs:189-218`).
   So `tables` cannot be read without `state`: the era of the tip says which `TxOut` type the values are.
   Key and value are each a CBOR byte string holding the MemPack bytes (`encodeMemPack = encodeByteArray . pack`, `cardano-ledger-binary/src/Cardano/Ledger/Binary/Encoding.hs:108-110`).
   The map is `encodeMap` with the version of the era's lowest protocol version (>= 2), which is `variableMapLenEncoding`: **a definite head up to 23 entries,
   an indefinite map (`0xbf` ... `0xff`) above** (`Binary/Encoding/Encoder.hs:397-408, 427-443`, `lengthThreshold = 23`). The keys are in the order of the `Map`, i.e. ascending.
3. `meta`: `writeSnapshotMetadata` (`Snapshots.hs:332-341`) writes the JSON of `SnapshotMetadata` (223-243): `{"backend":"utxohd-mem","checksum":<word32>,"tablesCodecVersion":1}` (the key order and no blanks as in
   every real file seen). The backends are `utxohd-mem`, `utxohd-lmdb`, `utxohd-lsm` (245-262); `tablesCodecVersion` 1 is the only one (`enforceVersion`, 206-221), "used in cardano-node 10.7".
   The checksum is `maybe crc1 (crcOfConcat crc1) crc2` with `crc1` the CRC of `state` and `crc2` that of `tables` (`V2/InMemory.hs:218-224`); `crcOfConcat` is the CRC32 of
   `word32Dec crc1 <> word32Dec crc2`, the two numbers written as decimal ASCII (`OC/Util/CRC.hs:20-26`).

### 1.3 The key and the value

* **Key** (`BigEndianTxIn`, `OCC/shelley/.../Ledger/Ledger.hs:338-356`): 32 bytes of transaction id (`Hash`, raw) then the index as a **big-endian** `Word16`
  (the `MemPack Word16` of the library is little endian; the node swaps it, so that the bytes sort like the `TxIn`; a test of the node proves the order,
  `ouroboros-consensus-cardano/test/shelley-test/Test/Consensus/Shelley/LedgerTables.hs:89-91`). This is the key Scarab's RocksDB already uses (K6): the stored keys are the table keys, in the table's order, with no conversion.
* **Value**: the era's `TxOut` in the `MemPack` format (section 4).

### 1.4 Reading a snapshot back

`loadSnapshot` (`V2/InMemory.hs:231-281`): reads `meta` (the backend must be `utxohd-mem`), reads `state` and its CRC, takes the tip, then `readIncremental` of `tables` with `valuesMKDecoder`
(`decodeListLenOf 1` then `decodeTablesWithHint` of the state's era: `decodeMap` with the version of the era, so definite or indefinite, duplicates refused from protocol 9), computes
`crcOfConcat checksumAsRead crcTables` and refuses the snapshot on a mismatch with `meta` (`ReadSnapshotDataCorruption`). The streaming reader of the converter reads the same file entry by entry
(`yieldCborMapS`, 373-400, definite or indefinite). The CRC is of the file bytes as read, so the checksum cannot be computed from a re-encoding; it is computed over the bytes written.

## 2. Tools and how a node or db-analyser takes a snapshot

### 2.1 db-analyser

`db-analyser --config <node config> --db <db> --in-mem --analyse-from <slot> <analysis>` initialises the ledger from the newest snapshot not newer than `<slot>` and replays to it; a block must exist
at that slot; it reads `<db>/immutable` and `<db>/ledger` only (`ouroboros-consensus/docs/website/contents/references/consensus_tools.md:85-150`). `--store-ledger <slot>` writes a snapshot as `<slot>_db-analyser`
(same document, 173-190). This is the acceptance tool of S5: a Scarab-written directory is accepted if `db-analyser --in-mem --analyse-from <slot> --count-blocks` loads it (no checksum, decode or era error) and replays the immutable
blocks that follow; `--store-ledger` on top gives a second snapshot whose `state` and `tables` can be compared with the Scarab-written ones: the `tables` compare as maps of decoded outputs, and by CRC only when every output is canonical (section 4.3).

### 2.2 snapshot-converter

`snapshot-converter` (`ouroboros-consensus-cardano/app/snapshot-converter.hs`; the library `Ouroboros.Consensus.Cardano.SnapshotConversion`, `src/unstable-snapshot-conversion/...`, also a `cardano-node` subcommand) converts between **Mem** and **exported LSM** snapshots
(`SnapshotConversion.hs:63-71, 175-199`). It copies `state` verbatim (`:191`), streams the tables through the target backend's sink, checks the input checksum against `meta` and writes a new `meta`. It requires the directory name to equal the slot of the state
(`checkSnapSlot`, `:286-297`, `WrongSlotDirectoryName`). The Mem sink writes `encodeListLen 1 <> encodeMapLenIndef`, the entries and a break, whatever the count (`V2/InMemory.hs:415-451`): so a converter-written `tables` is always indefinite, a node-written one
is definite up to 23 entries. The Mithril client wraps it as `mithril-client tools utxo-hd snapshot-converter` (manual, `bootstrap-cardano-node.md:525-551`).

### 2.3 Verified checksums

| Directory | `meta` | CRC32 of `state` | CRC32 of `tables` | `CRC32(decimal(state) ++ decimal(tables))` |
|---|---|---|---|---|
| Mithril preprod, `ledger/136511997` | 2668385107 | 3763850295 | 1172950672 | 2668385107 |
| devnet `fixtures/haskell-state/conway-4593` | 670422015 | 851909063 | 2831427093 | 670422015 |

(The CRC32 of `state ++ tables` is 1753435205 and 35398395, of `tables ++ state` 2319871413 and 1082265326: none matches.)

## 3. A Mithril cardano-database on disk

### 3.1 Layout

A restored database (`mithril-client cardano-db download`) is the node's `db/` directory: `immutable/<chunk>.{chunk,primary,secondary}` for the certified chunks, and, with `--include-ancillary`, `ledger/<slot>/{state,tables,meta}` of
the aggregator node's newest snapshot and the immutable trio of the next, unfinished chunk (`mithril-aggregator/src/services/snapshotter/compressed_archive_snapshotter.rs:225-296`: the trio of `immutable_file_number + 1` and, for node versions with UTxO-HD, **one** ledger snapshot, the latest).
The ledger directory is copied verbatim from the node, so the files are what section 1 describes. The `volatile/` directory is not shipped. The ancillary archive also holds `ancillary_manifest.json` (below).

Measured on the preprod cardano-database of epoch 319 (immutable file number 6320, created 2026-10-17, node 11.1.3; archive 440 MB, 1.05 GB unpacked): `immutable/06321.*`, `ledger/136511997/state` (32.5 MB), `tables` (1,014,553,955 bytes, **4,526,058 outputs**,
indefinite map, all Conway-typed), `meta` as in section 2.3. Slot 136,511,997 is three slots before 136,512,000 = 6320 x 21,600, the first slot of chunk 6320 (a chunk of the ImmutableDB is 10 k slots, 21,600 on preprod: `ouroboros-consensus-cardano/src/shelley/Ouroboros/Consensus/Shelley/Node/Common.hs:101-110`), so the ledger state is at the end of chunk 6319 and the node (or Scarab) must apply the certified chunk 6320 and the uncertified 6321 on top.

### 3.2 Which outputs occur

All 4,526,058 values of that file decode as Conway `TxOut`s by the grammar of section 4 (tags 0 to 5, no other): tag 0 2,535,824 (1.40M ada-only enterprise outputs, 0.87M base addresses with tokens, the rest Byron, pointer and others), tag 1 88,506, tag 2 988,407, tag 3 7,932, tag 4 (inline datum) 838,882 and tag 5 (reference script, native and Plutus V1 to V3) 66,507. No ada-only base-address output has tag 0: the constructor is the canonical one (section 4.3) in every one of the 4.5M entries on this network, since preprod's earliest eras are short. On mainnet, outputs carried over from Shelley and Mary
keep their old constructor (`upgradeTxOut` keeps it: `Alonzo/TxOut.hs:340`, `Babbage/TxOut.hs:232-238`, `Conway/TxOut.hs:41, 79`), so a reader must accept any constructor in any era from Alonzo on, and a writer cannot reproduce bytes it did not choose.

### 3.3 What the signature covers

The Mithril certificate covers the immutable files (digests in the `cardano-database` artifact, merkle root). The ancillary files are "files that can't be currently signed by the Mithril protocol: the last ledger state snapshot and the last immutable file" and "are signed using Ed25519 and the signature is checked against the provided `ANCILLARY_VERIFICATION_KEY`"
(manual, `bootstrap-cardano-node.md:486-492`). The archive holds `ancillary_manifest.json` = `{"data": {<relative path>: <sha256 hex>}, "signature": ...}` (`internal/cardano-node/mithril-cardano-node-internal-database/src/entities/ancillary_files_manifest.rs:12-80`; the client's check is `mithril-client/src/utils/ancillary_verifier.rs`). So the ledger state of
a Mithril database has the trust of one operator key, not of the certificate. This is the answer for #27: the import either verifies the manifest signature and digests (D4) or imports an unauthenticated state. The state can be cross-checked cheaply after import: the Haskell state carries the instant stake and the pool distribution, which S3 can recompute from the tables
(sum of outputs by stake credential); a mismatch refuses the import. That check catches a corrupted tables file but not a state that was forged consistently; only the key does that.

## 4. The MemPack `TxOut`

All byte rules below are those of `mempack` 0.2.1.0 (`src/Data/MemPack.hs`): a `Tag` is one byte (`packTagM`, 1541-1549); `Word16/32/64` are fixed width, **little endian** (`instance MemPack Word64`, 581, written with `writeWord8ArrayAsWord64#`); `VarLen` is base-128 with the **most significant
group first**, the high bit of a byte meaning "more follows", minimal length when written (`packIntoCont7`, 1432-1442; `unpack7BitVarLen`, 1448-1464; at most 10 bytes for 64 bits, the last byte checked, 1466-1480); a `ShortByteString`/`ByteString`/`ByteArray` is `Length` (a `VarLen Word`, 1492-1510) then the bytes (989-1007);
a `Hash` is its raw bytes with no length (`PackedBytes`, `cardano-base/cardano-crypto-class/src/Cardano/Crypto/PackedBytes/Internal.hs:114-140`).

### 4.1 The constructors

| Era | Type | Constructors (tag) | Source |
|---|---|---|---|
| Shelley, Allegra, Mary | `ShelleyTxOut` | 0 `addr value` | `CL/eras/shelley/impl/src/Cardano/Ledger/Shelley/TxOut.hs:64-77` (the tag 0 "for forward compatibility") |
| Alonzo | `AlonzoTxOut` | 0 `addr value`, 1 `addr value datahash`, 2 `stakeCred addr28 coin`, 3 `stakeCred addr28 coin datahash32` | `eras/alonzo/impl/src/Cardano/Ledger/Alonzo/TxOut.hs:164-196` |
| Babbage, Conway | `BabbageTxOut` | the four above, 4 `addr value inlineDatum`, 5 `addr value datum script` | `eras/babbage/impl/src/Cardano/Ledger/Babbage/TxOut.hs:133-215` (Conway reuses the type: `Conway/TxOut.hs:36-37`) |

Parts:

* `addr` is the compact address: `Length` and the bytes of the serialised address (`CompactAddr = ShortByteString`, `libs/cardano-ledger-core/src/Cardano/Ledger/Address.hs:415-417`, `compactAddr = serialiseAddr`, 427-429): the ledger's normalised form (a pointer too large for its fields is `Ptr 0 0 0`, `Credential.hs`), which is `Address.raw` in Scarab.
* `value` for Shelley and Allegra is `CompactForm Coin`: tag 0 and `VarLen` of the lovelace (`Coin.hs:157-167`). From Mary it is `CompactValue` (`eras/mary/impl/src/Cardano/Ledger/Mary/Value.hs:432-462`): tag 0 `VarLen coin` (ada only: the same bytes as the Shelley coin) or tag 1 `VarLen coin`, `VarLen numAssets`, `rep` as a byte string.
* `rep` (`Value.hs`, the comment 475-578 and `to` 580-700): A `Word64` quantities (little endian, `numAssets` of them), B `Word16` policy offsets, C `Word16` asset-name offsets, D the distinct 28-byte policy ids ascending, E the distinct asset names **descending** (so the empty name last, at the end of the rep). Offsets are from the start of the rep. The triples come from `flattenMultiAsset` (policy ascending, then name ascending, `Value.hs:924-929`), are **stably** sorted by the asset-name offset (`sortOn`, 594-595; i.e. by name descending, equal names keep policy order), and written in that order. There is no padding in the bytes (the picture shows
  word alignment; `repSize = 12 n + 28 p + names`, 658-675 and 696-698). The same name under two policies shares one entry in E. Decoding computes a name's length from the next greater offset or the end of the rep (`from`, 730-796). A value is compactable only if every quantity is in `[0, 2^64-1]` (`integerToWord64`), the coin too, and `44 n + 28 p <= 65535` (`isMultiAssetSmallEnough`, 710-712): the encoder reports a typed refusal otherwise.
* `stakeCred` is a `Credential Staking`: tag 0 script hash, tag 1 key hash, 28 raw bytes (`Credential.hs:105-118`). `addr28` is `Addr28Extra`, four `Word64` little endian (`Alonzo/TxOut.hs:97-111, 258-283`): `w0, w1, w2` are the first 24 bytes of the 28-byte payment hash as three **big-endian** words, `w3 = (last 4 bytes as a Word32) << 32 | networkBit<<1 | keyBit` with `networkBit` 1 for mainnet and `keyBit` 1 for a key payment credential (0 for a script). `datahash32` is `DataHash32`, the 32-byte hash as four big-endian words, each written little endian (`Alonzo/TxOut.hs:113-125, 285-291`).
* `datahash` (tag 1) is 32 raw bytes. A `Datum` (in tag 5) is a tag: 0 none, 1 plus 32 raw bytes, 2 plus the inline datum as a byte string (`libs/cardano-ledger-core/src/Cardano/Ledger/Plutus/Data.hs:278-295`); tag 4 holds the bare `BinaryData` (a byte string: the CBOR of the plutus data, without the `#6.24` wrapper).
* `script` (tag 5) is an `AlonzoScript`: tag 0 a native script as the byte string of its **original** CBOR (`MemoBytes`, `Allegra/Scripts.hs:257-260`, `MemoBytes/Internal.hs:130-142`), tag 1 a Plutus script: its language tag (0 V1, 1 V2, 2 V3 in Conway; 0 and 1 in Babbage, `Babbage/Scripts.hs:134-147`, `Conway/Scripts.hs:184-200`) and the script bytes as a byte string (`Alonzo/Scripts.hs:478-497, 642-652`; `PlutusBinary` derives `MemPack` over the `ShortByteString`, `Plutus/Language.hs:250-252`).

### 4.2 The key

`BigEndianTxIn` is `Hash 32` then the swapped `Word16`: 34 bytes (section 1.3). Scarab's key `Coding.encodeKey` is the same bytes.

### 4.3 Which constructor an output gets

`mkTxOut` (`Babbage/TxOut.hs:423-459`; `AlonzoTxOut` pattern, `Alonzo/TxOut.hs:303-333`): a basic output (no reference script, datum none or a hash) whose value is ada only and whose address is a base address with a staking *credential*
(`StakeRefBase`) gets tag 2 (no datum) or 3 (datum hash); otherwise no script: tag 0, 1 or 4 by datum (none, hash, inline); with a reference script: tag 5. Shelley to Mary have only tag 0. **The constructor depends on history**: an output upgraded from an earlier era keeps the one it had, so a base-address ada-only output can have tag 0 in a snapshot. A decoder takes
every constructor of the era; an encoder takes the canonical one; the property "the decoded output equals the original" is about the logical output, and "the re-encoded bytes equal the original" holds when the original was canonical (all 4.5M of the preprod file; section 8).

## 5. What the Scarab side is today

* **The stored value** (`UtxoStore.Coding`, `storage/.../UtxoStore.scala`, `encodeValue`/`decodeValue`): a tag byte and the output's original CBOR (tag 1) or the derived `Codec` (tag 0). The key is the 34-byte Haskell key (`RocksUtxoStore.scala:26-37`). The CBOR carries the `raw` bytes that the rules re-read (`TxOut.referenceScript`, `referenceScriptSize`, `ledgerSize`, `addressExcess`, `Coding.addressSpan` for the address scan of #113): a decoded MemPack output has none and must be given a canonical CBOR (section 8.3).
* **The snapshot V5** (`SnapshotV5.scala`, [architecture.md](../architecture.md#modules) "Snapshot format"): `"SCRBSNP5" | sidecar | Haskell state bytes | SHA-256`, the Haskell bytes being a `state` file; the UTxO is a RocksDB checkpoint paired with the snapshot. The sidecar holds what a Haskell `state` has no place for: the anchor, the eras observed, the gaps, and for a Shelley-based chain the node's `Utxo` bookkeeping (stake by credential and pointer, count, total, stamps), the node's full protocol parameters (every field in every era, completed from the genesis files) and the `MirrorExtras` (bootstrap and Byron chain-follow state, the epoch of the last tick, the TPraos previous-epoch nonce, the Plutus statistics).
* **`HaskellLedgerFile`** (`storage/.../haskell/state/HaskellLedgerFile.scala`) reads and writes `state`; `SnapshotMeta` parses `meta` (its comment on the checksum was loose and is corrected with S1; checking and writing it are S2).
* **`import-haskell-db`** ([running.md](../running.md)) imports the immutable chunks of a Haskell database and replays them; it ignores `ledger/`.

## 6. Stages

| Stage | Content | Level | Depends on |
|---|---|---|---|
| **S1** (#143) | The MemPack `TxOut` codec in `ledger` (all eras Shelley to Conway), goldens from real node files, properties, measurement against the CBOR value, the recommendation on adopting it. No change to what the running node stores. | 3 (a persisted-value format is defined; nothing persisted changes) | none |
| **S2** | `tables` writer and reader and the `meta` file in `storage`: a streaming reader (definite or indefinite map, constant memory, CRC32 as read), a streaming writer from RocksDB in key order (the node's framing rule, CRC32 as written, converting the stored value to MemPack when it is CBOR), `meta` writer with the checksum rule, `meta` verification. Tests on the committed devnet fixtures and on the preprod file (env-gated: write 4.5M entries from a database, compare with the reference file). **Byte or CRC identity with a node-written file holds only when every output is canonical**: it holds on the preprod file (all 4,526,058 outputs, section 8.2) and does not on the devnet fixtures of Alonzo, Babbage and Conway, whose genesis outputs are tag 0 where the encoder writes tag 2, nor likely on mainnet. So the reader/writer test passes the entries through unchanged for the byte-identity check (re-framing the same value bytes, CRC equal to `meta`), and compares decoded outputs where it converts. | 3 | S1 |
| **S3** | `import-ledger-snapshot --haskell-ledger <dir> --db <dir> --config ...`: verify `meta` (and, with D4, the Mithril manifest), `HaskellLedgerFile.decode` of `state`, stream `tables` into a RocksDB batch load while accumulating the node bookkeeping (`Utxo`: stake by credential and pointer, count, total), take the protocol parameters from the state and complete them from the genesis files, fill the sidecar (`NodeExtras.empty`, gaps none, `observed` from the telescope), write a V5 snapshot and its checkpoint. Cross-check the instant stake of the state against the recomputed one. Refuse Byron, a snapshot whose tip is beyond the local immutable chunks, an unknown era, and tables of the wrong era (typed). Then `follow` replays the chunks after the snapshot (preprod: chunks 6320 and 6321). | 3 | S2 |
| **S4** | `export-ledger-snapshot --db <dir> --out <ledger dir> [--slot N]`: the reverse: the newest V5 snapshot's `HaskellLedgerFile` bytes become `state` unchanged (they are `state` by construction), the UTxO checkpoint is streamed to `tables`, `meta` is computed. Refuses a state the node holds in a form the Haskell encoding cannot carry (the `gaps` of the sidecar). | 3 | S2 |
| **S5** | Acceptance. (a) `db-analyser --in-mem --analyse-from <slot> --count-blocks` on a Scarab-written directory next to the same ImmutableDB (a devnet database and a Preview or preprod database), then `--store-ledger` and a diff of the two directories' CRCs. (a') The comparison of a Scarab-written `tables` with a node-written one is a comparison of decoded outputs; the CRC is compared too on preprod, where it is expected to be equal. (b) A Scarab node started from the Mithril preprod directory (S3), following to the tip, its ledger state compared with a Haskell node's at epoch boundaries (the K1/K7 comparison). (c) A Haskell node (reference image) started on an S4 export, extending it by a few blocks. | 4 | S3, S4 |

Sizes (agent-days, single session each): S1 2 (done), S2 2, S3 4, S4 1.5, S5 3. Streaming cost to expect for a preprod-size set (4.5M outputs, 1 GB): the encoder at S1's measured rate plus the RocksDB scan; S5 records the real times.

## 7. Risks and what they depend on

* **The sizes of the original encoding** (section 8.3): an imported output's `ledgerSize` and `valueSize` come from the canonical encoding, which differ from the original's for 2.5% and 0.9% of the outputs of a young network; nothing reads them after the output is created, and the reference-script data, which is read, is equal on all 1,731,810 compared outputs.
* **The constructor depends on history** (section 4.3): a Scarab export is canonical, a Haskell node's file may not be. Logical equality is the comparison; byte equality is not promised for mainnet-age outputs.
* **State fields the Haskell encoding does not have** (gaps): the sidecar's `Utxo` bookkeeping and the node's full parameter set are recomputed at import (S3); a snapshot exported from a state with gaps is refused (S4) rather than invented.
* **The uncertified ancillary** (section 3.3): D4.
* **Mainnet size**: the `tables` of mainnet is larger than the preprod one by an order of magnitude (not measured here); the streaming reader and writer are constant memory and the RocksDB batch load is bounded by `WriteBatch` size, but the time (hours) is a measurement for D9/#69, not S2.
* **`tables` for Byron** is `a0` (an empty map, `Ledger.hs:189-195`); a Byron-tip snapshot is refused in S3/S4 (D7).
* **Drift**: `tablesCodecVersion` is 1; a newer version changes the format (`enforceVersion`). The reader refuses an unknown version with a typed error; the pin moves with the reference node (ADR 0001).

## 8. Stage 1 as built (#143)

### 8.1 What exists

`ledger/src/main/scala/xyz/kd5ujc/scarab/ledger/mempack/`:

| File | Content |
|---|---|
| `MemPack.scala` | the format primitives (`Packer`, `Unpacker`: tag, little-endian words, `VarLen`, byte strings) and the closed `MemPack.Failure` (truncated, trailing bytes, over-long `VarLen`, unknown tag at a named site, a constructor the era lacks, a bad multi-asset representation, a quantity out of range, too many assets to compact, an address or script the node refuses, a script without bytes) |
| `MultiAssetRep.scala` | the flat multi-asset representation of section 4.1, both directions |
| `TxOutMemPack.scala` | `encode[E](TxOut)`, `decode[E](Bytes)`, and the lower level `encodeParts`, `decodeParts`, `parts`, `toTxOut` over `TxOutParts(address, value, datum, script)`; `E` is the era (`ShelleyBasedEra[E]`), so a Shelley output with a token, a Babbage output with a Plutus V3 reference script and an Alonzo output with an inline datum are typed refusals |
| `CanonicalCbor.scala` | the CBOR a decoded output carries as its `raw` (section 8.3) |

Nothing outside `ledger/.../mempack`, the tests, the fuzz target `mempack-txout` and one fixture directory changed; the node stores what it stored.

### 8.2 Evidence, and where each golden comes from

The goldens are bytes written by the reference. Building `cardano-ledger` in a Haskell container for a one-off program was not attempted (hours of dependency builds against the package index, for bytes a running node already wrote; ADR 0001); the node that wrote them is the reference.

* **`fixtures/mempack/preprod-conway-sample.tsv`**: 95 outputs of the UTxO table of the Mithril preprod snapshot at slot 136,511,997, node 11.1.3 (provenance, URL and digests in `fixtures/mempack/README.md`): every constructor (0 to 5), base (key or script credentials), enterprise, pointer and Byron addresses, ada-only and multi-asset, datum hash and inline datum, native, Plutus V1, V2 and V3 reference scripts, values of 24 or more policies, one name under two policies, the empty name with others. Each line also carries what the independent Python decoder (`scripts/oracle/mempack.py`, written before this stage) reads from the bytes; the Scala decoder agrees on all 95, and packing what it decodes gives the bytes back.
* **The seven devnet snapshots** (`fixtures/haskell-state`, node 11.1.3): the outputs of every era Shelley to Conway decode and pack back. The devnet's genesis outputs show the history dependence of section 4.3 on real bytes: in the Alonzo, Babbage and Conway snapshots they are tag 0 (compact) although they are base-address, ada-only outputs that a fresh build would pack as tag 2. The decoder reads them; the encoder, which chooses the canonical constructor, packs them as tag 2, and the test accepts exactly that difference.
* **All 4,526,058 outputs of the preprod table** (`MemPackJoin scan`, env-gated suite `MemPackJoinSuite`): every value decodes, and packing the decoded output gives the original bytes for every one (0 differences). Constructor counts: tag 0 2,535,824; 1 88,506; 2 988,407; 3 7,932; 4 838,882; 5 66,507.
* **The join with this node's own UTxO values** (`MemPackJoin join`): the UTxO checkpoint of the build ce8c438 (preprod, slot 80,177,772, 1,797,769 outputs, key layout of the builds before K6) against the table, both sorted by key and walked together: 1,731,810 outputs are in both (the other 65,959 of the checkpoint have been spent since, the other 2.79 million of the table created since). For every one of them the output decoded from the stored CBOR (what the blocks said) and the output decoded from the node's MemPack bytes are the same output (address, value, datum, script), and **packing the first gives the second's bytes exactly: 0 differences in 1,731,810**. This is the check that matters for export: it shows that the constructor choice, the address normalisation, the multi-asset layout and the script bytes of an output built from block CBOR are those of the reference, not only that the decoder inverts the reference's choices.
* **Properties** (`TxOutMemPackSuite`, every era): parts pack and unpack to themselves; the canonical CBOR of an output reads back to the same output and gives the same parts; the tag is the one `mkTxOut` chooses; every proper prefix of a valid encoding is a typed failure; arbitrary bytes never throw. **`VarLen`** vectors, including 2^64 - 1 and the refusal of an eleventh group. **Fuzz**: target `mempack-txout`, seeds the 95 real outputs.

### 8.3 `TxOut.raw`, the trap

The node's `TxOut` is the decoded fields **and the original encoding** (`raw`), and four things re-read it: `referenceScript` and `referenceScriptSize` (the reference-script fee, `TxChecks.scala:420, 577`, `Bbody.scala:78`, `Phase2.scala:51`, `MempoolState.scala:281`), `ledgerSize` and `addressExcess` (the minimum-UTxO rule, `TxChecks.scala:123`), `Coding.addressSpan` (the address scan of #113) and `UtxoCodecs.txOut` (the answer to a node-to-client `GetUTxOByAddress`, which writes the datum and script items from `raw`). A MemPack output has no original encoding (the reference's `TxOut` does not either: it re-encodes to size it). `decode` therefore builds the **canonical CBOR** the reference writes for an output it holds only compactly (`EncCBOR (BabbageTxOut era)`: the two- or three-item array when there is no inline datum or script, else the keyed map; a value as a bare coin or `[coin, {policy: {name: quantity}}]`, maps above 23 entries indefinite) and puts it in `raw` with `valueSize`; the script bytes follow from it; `ledgerSize` and `valueSize` do too, but not always as the stored original gave them (below). A decoded output must never go through `UtxoCodecs.txOut.encode` with an empty `raw` (it would drop the datum and the script silently); with the canonical `raw` it does not. **What the join shows about `raw`** (1,731,810 outputs, the canonical `raw` of the MemPack output against the original CBOR the node stored from the block):

| Compared | Differs in |
|---|---:|
| `referenceScript` (kind, hash, bytes) | **0** |
| `referenceScriptSize` | **0** |
| `UtxoCodecs.txOut` (the node-to-client answer) | 1 |
| `ledgerSize` | 43,129 (2.5%) |
| `valueSize` | 14,732 |
| `raw` itself | 317,088 (18.3%): 259,890 are the keyed map with only address and value (`a2`) where the canonical form is the array (`82`), 28,397 the map with a datum hash (`a3` against `83`); 16,676 are arrays and 12,125 maps that differ inside (a value written `[coin, {}]` with an empty asset map against the bare coin, and other non-minimal forms) |

So **nothing the rules read from a stored output's reference script differs**, and S3 as designed is not blocked: imported outputs give the same reference-script fee and the same script for execution. What differs is the size of the encoding the output arrived in. The Haskell node keeps no original encoding of an output in its UTxO table (the compact form is all there is, and anything it sizes it encodes again, as the bare coin for the `[coin, {}]` output), so the canonical `raw` is what the reference has for such an output; and the minimum-UTxO rule (`TxChecks.minUTxO`) is applied to the outputs of a transaction as it arrives, never to an output already in the set, so no verdict reads these sizes of an imported output. The one node-to-client answer that differs would, from a MemPack output, be the reference's re-encoded form. These counts are the evidence for the statement; `MemPackJoin join` prints them.

The property tests compare the canonical CBOR with `TxOut.fromBytes` and the parts with `parts`, not case-class equality between an output from blocks (whose `raw` is whatever the transaction carried) and a decoded one.

### 8.4 Size and speed against the stored CBOR value

Input: the joined set of 8.2 (the outputs of the slot-80 M checkpoint that are still unspent in the table: a random sample by key order, since the keys are hashes) and the whole table. Method as in `FormatBenchSuite`: one JVM (JDK 21, one thread), the first 400,000 joined outputs held in memory, three warm-up runs then the median of seven timed runs, the median of three rounds; ns per output. The stored value is `Coding.encodeValue`'s (a tag byte and the output's own CBOR). `MemPackJoin join <tables> <checkpoint> bench` prints it.

| 400,000 joined outputs | stored CBOR value | MemPack |
|---|---:|---:|
| bytes per output | 230.6 | 231.6 (x 1.004) |
| decode to the node's `TxOut` (ns) | 2,545 | 2,664 (of which parts only 562) |
| encode from the node's `TxOut` (ns) | 2,929 (it re-reads the CBOR to check it round-trips) | 2,017 (from parts 1,632) |

The whole table, one cold pass of 4,526,058 outputs (so slower per output than the warmed figures): 186.1 bytes per output in MemPack against 185.8 for the canonical CBOR value; decode to parts 982 ns, to `TxOut` 2,747 ns, encode 2,339 ns. 31 s for the file including the I/O and the check.

What the numbers say: the MemPack value is **not smaller** than the CBOR value on preprod (parity: the 57-byte base address and the hashes dominate both, and the compact form saves a few bytes only where the CBOR has heads), and **the full decode is not faster** (2.66 against 2.55 microseconds), because rebuilding `raw` and the `Address` costs 2.1 of the 2.66. Only reading the fields without the node's `TxOut` is fast (562 ns, 4.5 times faster).

### 8.5 Recommendation on adopting MemPack as the stored value (decision D3)

**Do not adopt it now.** The reasons: no size gain (issue #143's premise, "more compact than our CBOR values", does not hold on preprod); no decode gain for the rules; a persisted-format change with a migration of every checkpoint (ADR 0006; the snapshot fingerprint golden guards it) and a re-derivation of `raw` on every read, in exchange for the one thing it would save: converting each output once at an import or an export, which at these rates is 2 to 5 microseconds an output, **about 12 s of CPU per 4.5 million outputs** and linear in the size of the set (to be measured on mainnet), next to the minutes the RocksDB bulk load and the 1 GB of I/O take. S2 to S4 convert at the boundary, streamed.

Revisit when one of these holds: (a) `TxOut` stops depending on `raw` (the reference-script size, the minimum-UTxO size and the node-to-client answers computed from the fields, as the reference does), after which the stored value can be the parts and the 562 ns read is the cost; (b) a mainnet measurement (the mainnet UTxO set has far more small ada-only outputs, where the compact forms win) shows a size saving worth a migration; (c) the address scan of #113 needs a faster path than the CBOR head: with MemPack the address of tags 0, 1, 4 and 5 is at a fixed place (tag, `Length`, bytes) and needs no head parsing, but tags 2 and 3 hold the payment hash and the stake credential and no address bytes, so a "starts with" test needs the 57-byte address rebuilt, which `fromRep` does in a few ns.

Cheap improvements found while measuring, not made (the numbers above are before them): `choose` re-decodes the address to find out whether it is a base address (`Address.decode` builds a `Vector[Byte]` of it), and `toTxOut` does it again; building the `Address` of tags 2 and 3 from the fields instead would remove most of the 2.1 microseconds of 8.3's rebuild for the 22% of the outputs that are tags 2 and 3.

### 8.6 What this changes in the Mithril plan (E1 #27, D9 #69)

* The state side is further along than the plan said: the preprod `state` of a Mithril snapshot round-trips today (section 0). S3 is the tables, the bookkeeping the sidecar needs, and wiring.
* The import converts each output once (MemPack to the stored CBOR value, about 5.5 microseconds in all); it does not need the stored value to change, and the node's rules are untouched.
* The ledger directory of a Mithril database is unauthenticated by the certificate (section 3.3): E1's acceptance should say whether the import verifies the ancillary signature (D4).
* The snapshot is behind the certified tip (here by about one chunk), so the first thing a node does after import is replay the chunks after the snapshot's slot; `import-haskell-db` and the follower already do that for the blocks (the `ledger/` directory it ignores is what S3 reads).
* A mainnet import is a measurement of time and memory (the table is larger than the preprod one by an order of magnitude, to be measured), not a new mechanism; D9's "compare the ledger with the Haskell node's at every epoch boundary crossed" is the K7 comparison on the states of the two nodes after the same replay, and `db-analyser --store-ledger` gives the reference state at a chosen slot from the same database.

## 9. Stage 2 as built: streaming tables and metadata

`storage/haskell/state/SnapshotTables` is an immutable incremental framing decoder. It accepts a
definite one-element array containing a definite or indefinite map, definite byte-string keys and
values, and non-shortest length heads. Keys have the reference's 34-byte layout. A declared length
does not allocate its body; the decoder retains only bytes actually received for the current entry.
`SnapshotCrc` travels with the decoder and computes CRC32 over the original file bytes, including
noncanonical framing. The final summary is available only after the closing map and the end of the
input have been checked. An input with trailing bytes or an incomplete entry has no completed summary.

`SnapshotTableStream` exposes the decoder as an fs2 event stream, a framing fold and an era-indexed
`readInto` operation. `readInto` additionally decodes each MemPack value, then calls an explicit
`SemanticSink[F, E]`. **Framing is not whole-table validation.** The reference accepts unsorted unique
maps and rejects duplicates from protocol 9 (`cardano-ledger` f649f97,
`Binary/Decoding/Decoder.hs:809-856`); earlier eras retain the last value. A sink supplies those
semantics. Tests use an in-memory map; S3 must use disk-backed duplicate checking before making an
imported table visible. The framing reader deliberately imposes no ordering requirement.

The streaming writer requires ascending keys, verifies the supplied count, and follows D5: a definite
map through 23 entries, otherwise an indefinite map. Its raw-entry operation preserves the MemPack
bytes exactly, including constructors retained from earlier eras. `writeStore` consumes
`UtxoStore.entries` in key order and converts each stored CBOR output to MemPack with the S1 codec.
The RocksDB stream owns and closes its iterator and preserves the typed corruption checks of every
other enumeration; the memory store sorts its existing in-memory set. The caller holds the store
offline, as D6 requires. Resources and publication belong to S3/S4; these operations do not add
import/export commands or change V5 or the internal UTxO encoding.

`SnapshotMeta.fromCrcs`, `encode` and `verify` implement the reference's compact three-field JSON and
`CRC32(decimal(stateCRC) ++ decimal(tablesCRC))`. Verification refuses the wrong backend, unknown
codec version and checksum mismatch through closed errors. Callers must verify metadata after fully
consuming the table, and keep sink writes staged until that succeeds. Ancillary authentication remains
the S3 import requirement of D4; CRCs establish integrity, not provenance.

**Metadata errors as built (M11-E/#82).** `MetaError` distinguishes `JsonSyntax` (retaining
Circe's `ParsingFailure`), `ExpectedObject` (the actual JSON), `MissingField` and
`FieldTypeMismatch` (the named `MetaField` and actual JSON). Backend, Word32 checksum,
tables-version and CRC verification errors keep their existing cases. Import/export retain the
cause in `Error.Metadata`; a refused import does not publish a snapshot or alter the database.
The reference JSON bytes, field-validation order, integral-decimal and extra-field acceptance,
CRC rule, V5 and stored UTxO values are unchanged. `SnapshotMetaSuite` covers the error cases and
boundaries; `snapshot-meta` fuzzing starts from all six Shelley-based fixture metadata files and
checks parse/encode/parse identity. Other storage codec and ancillary-manifest message cases
remain tracked by M11-E; this slice does not complete #82.

S2 validation: 22 focused tests pass, including all 4,526,058 outputs of the pinned preprod table
converted and streamed to a byte-identical 1,014,553,955-byte file (50.1 s). Full `check`, scalafix
and the 64-target four-seed decoder campaign pass (25.6 million cases), including the new table
framing target and its wide-input allocation guard. A private `restart` run stops in 0.7 and 0.6 s,
writes both shutdown snapshots, restores the snapshot and retains V5 throughout. The fixture
comparison verifies the committed SHA-256 pins; actual snapshot authentication remains the S3/D4
requirement.


## 10. Stage 3 as built: offline import

`import-ledger-snapshot --haskell-ledger <ledger/slot> --db <database>` reads the Mem backend
and publishes a V5 snapshot with its RocksDB checkpoint. The existing immutable chunks and network
identity are read-only inputs. The exact snapshot block, including its hash, number and era, must be
present in the local immutable index and data; its stored CRC and decoded header are checked without
opening the recovering/migrating ImmutableStore. Import refuses Byron, origin, a foreign network,
an unavailable point, unknown state/codec shapes, unsupported backend, wrong-era MemPack outputs,
metadata damage and inconsistent instant stake through closed errors.

D4 is enforced by default: the operator supplies `--ancillary-verification-key <file>` in Mithril's
published `ancillary.vkey` format (hex-encoded JSON containing 32 octets). The manifest defaults to
`ancillary_manifest.json` at the archive root above `ledger/<slot>`; `--ancillary-manifest <file>`
can name it explicitly. Every listed file must exist and match, and `state`, `tables` and `meta`
must all be listed. The complete extracted ancillary archive is needed, including its unfinished
immutable trio. `--no-verify-ancillary` explicitly selects an unauthenticated ledger; the JSON report
records `ancillaryVerified: false`. It cannot be combined with verification arguments.

The signature implementation follows Mithril 769d862's
`internal/cardano-node/mithril-cardano-node-internal-database/src/entities/ancillary_files_manifest.rs:153-160`
and `mithril-client/src/utils/ancillary_verifier.rs:59-86`: SHA-256 over ordered UTF-8 path bytes
followed by ASCII lowercase digest text, signed as the raw 32-byte hash, excluding the signature.
This is neither JSON bytes nor the hex representation of the hash. Mithril's
`mithril-common/src/crypto_helper/ed25519.rs:100-111` uses `verify_strict`; Scarab reuses its typed
strict public-input verifier. It also requires canonical point encodings, a deliberately stricter
local trust boundary for operator-supplied keys and signatures. The pinned preprod signature passes.

Local input safety additionally refuses duplicate JSON object fields; paths that are absolute,
traverse parents or normalize ambiguously; invalid UTF-8 names; and symlinks. These restrictions prevent a local manifest from
selecting files outside its root. Files are copied to an owned sibling staging directory while their
actual consumed bytes are hashed; decoding subsequently reads only those staged copies. Verification
therefore does not authorize a later second read of mutable source bytes.

The typed era selects the S2 MemPack codec and era-low binary version. The full-map decoder
(`Shelley/Ledger/Ledger.hs:376-387`, consensus 82ecba3) uses `eraDecoder`, independently of the current
protocol-parameter major: Conway rejects duplicate keys, and earlier eras retain the last value.
The sink uses batches of 1,024 entries, checks prior keys in the staged RocksDB and handles duplicates
inside a batch too. Input need not be sorted. Bookkeeping tracks unique count, lovelace total and
stake by credential/pointer; the final instant stake must equal the original state. Batch memory is
bounded; the stake maps remain proportional to distinct stake credentials, as the node's UTxO handle
requires.

The Haskell state section is retained byte for byte. The sidecar has empty gaps because these are
reference state fields, observed era bounds from the original telescope, `NodeExtras.empty`, and
reconstructed offline bookkeeping. Parameter completion copies every field actually encoded by the
era, including zero, and fills only absent fields from genesis. Protocol bookkeeping starts at the
state's epoch; the TPraos-only previous nonce, which the reference state does not contain, starts
neutral. TPraos validation uses its actual chain-dependent nonces, the next epoch tick replaces that
bookkeeping value, and TPraos-to-Praos translation uses the current epoch nonce. No Haskell field or
gap is synthesized for that node-only default.

`utxo/`, `snapshots/`, `volatile/` or `ledger/` collisions are refused. Staged store/checkpoint
resources close before publication. Parent directories are forced to disk, the fresh UTxO artifacts
move first and the snapshot moves last; caught failures roll back only artifacts this import moved, using `Handle[F, Error]` scopes rather than the throwable channel. A real second-rename failure under `EitherT[IO, Error, *]` proves this cleanup after the UTxO move.
Cleanup is best effort: if the filesystem also refuses removal, the original publication error is preserved and an orphan may remain for inspection. Cancellation attempts staging cleanup and releases ownership. A process crash can leave an unreferenced staged
or UTxO directory; it cannot publish a snapshot before its checkpoint. Inspect and remove that orphan
before retrying; import never replaces existing artifacts. The V5 schema, fingerprint and stored CBOR
remain unchanged.

A retained sibling lock inode coordinates the new `follow`, `import-haskell-db` and
`import-ledger-snapshot` commands, before writable stores open. Canonical database aliases share it.
It does not interoperate with older Scarab builds or Haskell node locks: the operator must keep both
input and destination offline and stable. No lock is acquired by opening an existing RocksDB, and a
failed import leaves preexisting database file bytes unchanged.

Tests cover the six original Haskell fixture tables and their exact instant stake, captured-state
imports in all six eras, original state bytes, V5/checkpoint readback, signature/digest/coverage faults,
unsorted and duplicate maps across disk batches, cancellation, alias ownership and refusal invariants.
The authenticated preprod import loads all 4,526,058 outputs in 57.1 s, verifies all six signed file
digests and CRC/metadata, retains the original 32,511,416-byte state and restores its V5 checkpoint.
Its inputs are the pinned ancillary archive and previously recorded local immutable chunks; no public
follower was started. S4 export is described below; S5 reference replay, cross-node startup and
preprod epoch/captured-point comparisons are recorded in section 12. The import result alone
does not establish those later acceptance claims.

S3 validation includes the full Docker `check` and scalafix, 66 decoder targets with 400,000 cases
each, 159 script tests, and a private restart on the shared ownership path: 16 blocks, two graceful
stops of 0.6 seconds, two shutdown snapshots, one restore, no errors and only V5 snapshot files.
The container runtime binds the same host sibling lease inode beside `/db`; the unwritable root
of a non-root container is not used as a different ownership namespace.

The initial preprod recording permits 2,377 post-import blocks, ending at slot 136,565,931 /
block 5,303,245. A later read-only capture from an existing synced node supplies epoch 320
and its reported synced point. Section 12 records those comparisons and their fixed-capture
scope; no new public-network synchronization is part of this work.

## 11. Stage 4 as built: offline export

`export-ledger-snapshot --db <dir> --out <ledger dir> [--slot N]` selects the newest existing
snapshot or an exact unsigned-64-bit slot. It verifies the file checksum, V5 magic/schema and
header/sidecar anchor, refuses Byron, gaps, embedded tables and unsettled bookkeeping, and opens
only the checkpoint with the snapshot's exact stem and persisted stamp. Missing source paths are
refused before taking a lease or creating a parent directory. No older format is migrated.

`UtxoCheckpoint` exposes only its stamp and sorted entries. Its RocksDB handle is read-only, with
native diagnostic files directed to staging outside the source. `SnapshotTableStream.writeOutputs`
converts each existing CBOR value to the era's MemPack type and checks the exact expected count
and ascending keys. The Haskell section becomes `state` unchanged; `meta` is computed from the
original written CRCs. V5, the sidecar schema and internal UTxO value encoding are unchanged.

The output must be outside the source database. The command closes and forces staged files,
removes closed-reader diagnostic logs before publication, forces the directory and publishes the
whole owned stage as `<slot>_scarab` by atomic directory rename. Successful publication leaves no
owned stage for a later finalizer to clean. An existing target is refused. Cancellation or failure
runs best-effort staging cleanup; a caught failure after rename rolls back the fresh output through
the typed error channel.
If the operating system also refuses rollback, the fresh directory can remain for offline inspection;
the original publication error is retained and existing outputs are never overwritten.
Required diagnostic deletion, file flush/force/close and directory persistence run in the normal
effect before publication, so their typed errors propagate. Resource fallback cleanup is best
effort on an existing failure or cancellation; an operating-system refusal can leave owned staging
for offline inspection. `Resource[EitherT]` discards a domain `Left` raised only by a finalizer,
which the native regressions establish; it is not used to report required persistence errors.
The shared sibling lease coordinates participating new Scarab commands; older/Haskell writers
still require the source to be offline.

All six original node 11.1.3 Shelley-based fixtures export with exact `state` bytes, equal decoded
outputs and valid metadata while source files/directories are read-only; every source file's
hash and path remains unchanged. Regressions cover missing sources, gaps, Byron, old formats,
checksum corruption, absent/exact slots, output collisions/ancestry, mismatched checkpoint count
and stamp, ownership refusal and cancellation while streaming a 100,000-entry table.

The preserved authenticated preprod S3 checkpoint exports all 4,526,058 entries in **30.8 s**.
Both `state` (32,511,416 bytes) and `tables` (1,014,553,955 bytes) are byte-identical to the
pinned Haskell originals, and metadata is equal. This byte claim is for this canonical table;
noncanonical historical constructors are compared as decoded outputs, as D5 requires. Every
source database file's SHA-256 remains unchanged. The full Docker check, including scalafix, and
21 focused export/publication/CLI tests pass. This export alone does not close #140.


## 12. Stage 5: reference acceptance from recorded inputs

The recorded inputs permit bounded comparisons without a new public-network synchronization.
On pre-integration source `0b2efe56`, the reference is `cardano-node` 11.1.3; every reference tool runs with
Docker networking disabled. The Scala follower reads a fresh copy of existing immutable blocks,
with an unreachable loopback peer and an isolated local socket. Original import/export artifacts
and configuration files are preserved; tools that can write receive separate working copies.

A previously recorded private chain contains 176 blocks through slot **3,436**, block **175**,
hash `97c8eb26a29ee8f2183fcad7c2dfb907be28a82464026da87ad35f9044c0e9f9`.
The reference analyser creates an authentic nine-output snapshot at slot 7. S3 imports that
locally produced snapshot with the explicit unverified-ancillary option, and S4 exports it as
`7_scarab`. `db-analyser --analyse-from 7 --count-blocks` accepts the export and counts the
175 following blocks. Full-ledger-validation snapshots at slots **570, 1,002, 1,523, 2,053,
2,525 and 3,010** match the existing Scala oracle projection at all six epoch boundaries, with
zero differences. The Scala run starts from a fresh imported slot-7 checkpoint, replays all
175 blocks and shuts down successfully. An earlier run replayed correctly but failed because its
relative local-socket parent did not exist; it is not counted as a successful startup.

A real reference node also starts with `7_scarab`, logs `ReplayFromSnapshot` at slot 7,
replays the recorded immutable tail and closes at slot 3,436 with exit status zero using
`--shutdown-on-slot-synced 3436`. No additional cluster or downloaded blocks are involved.
At the mid-epoch endpoint, every existing projected field except `rewardUpdate` agrees:
Haskell still holds `Pulsing`, which the Python projection presents as null, while Scala holds
its eager `Complete` result. The as-built eager behavior is documented in
[ledger-mirror step 2](ledger-mirror-step2.md), section 4 J2.3; the deferred representation
work is J3.5/M11. This is not a claim that the unfinished reference rewards already equal
Scala's complete result, and no field is masked to obtain a whole-endpoint pass.

For preprod, the authenticated S3 checkpoint and exact S4 export are at slot **136,511,997**,
block **5,300,868**. The initial immutable recording contains **2,377** later blocks, ending at
slot **136,565,931**, block **5,303,245**, hash
`deb213bccc724597e8e5e9ce1ed109a42277bf06088910398a10809233b2aa9f`.
Haskell fully validates that tail from the S4 export, and Scala restores a fresh copy of the
authenticated import and replays it. The unchanged oracle reports zero differences at the endpoint.

A read-only capture from an existing synced preprod node extends the recording across epoch
320. The copied immutable files are bound by SHA-256, each block CRC is checked, and the old
chunk/index prefixes are identical. The first epoch-320 block is slot **136,598,413**, block
**5,304,663**, hash
`cc080f9770e2d1da687685e2b04366c3f062a96a09a72c63bcfca546299463dd`.
The captured immutable endpoint is slot **136,602,570**, block **5,304,848**, hash
`af06340f3a01364be13b3f17a0553c5f273ddf9706833ded2f82ee33a5098bdc`.
A fresh authenticated-import copy replays **3,980** post-import blocks. Haskell independently
loads the original gap-free S4 export and fully validates the same chain. Both the first boundary
block and immutable endpoint match the existing oracle projection with **zero differences**.

The node's immutable tip lags its selected chain by the stability window, so it is not labelled the
live tip. A separate read-only capture of its volatile records supplies the selected chain through
the exact point the node reported as **100% synced** at query time: slot **136,651,599**,
block **5,307,009**, hash
`ba5a197afa446662805cee82b483b9dc4e5c570c14b7015a6c43ae192826133f`.
All **3,279** volatile-record CRCs are checked; **33** recorded rollbacks are applied, and
**2,161** selected blocks extend the captured immutable anchor. Independent verification checks
all **7,109** reconstructed source entries, original payloads, header hashes, slots, block numbers,
previous-header links and rollback targets. Only copied records are reconstructed; the source node
is not restarted or opened through a writable database API.

A separate fresh authenticated-import copy replays **6,141** post-import blocks to this reported
point, and Haskell fully validates the same raw chain from the original S4 export. The unchanged
oracle again reports **zero differences**, including the UTxO digest, rewards, governance and
consensus fields. Both Scala runs start from the original imported checkpoint, finish at the
specified point and shut down successfully. Original authenticated database file hashes remain
unchanged. The reference ImmutableDB indexes are made by the existing `oracle.export` helper
from original raw blocks; missing earlier chunks are not fabricated. An attempted use of
`import-haskell-db` on this sparse recording is correctly refused because chunk zero is absent;
that attempt is not counted as replay acceptance. The corrected fixture copies only verified
current-format immutable files into fresh local working databases.

These results compare the existing oracle projection at exact points; they do not assert equality
of arbitrary hidden/internal state. Catching up to a point an already synced node reported is a
fixed-capture acceptance result. It does not establish ongoing public-network following or
continuous comparison at later epochs, which remains K7/[#68](https://github.com/KD5UJC-XYZ/scarab/issues/68).
No new public-network follower or synchronization is used for these runs.

The earlier private and preprod replay snapshots acquire the existing `NonMyopic` and
`ShelleyTransition` mirror gap flags. S4 correctly refuses to export them, so there is no
post-replay endpoint table/CRC export claim; the gap-free imported baseline retains its earlier
byte-identical preprod table proof and successful Haskell load/replay. Gap tracking remains
J3.1/[#129](https://github.com/KD5UJC-XYZ/scarab/issues/129), and the private pending-versus-eager
reward representation remains J3.5/M11. Those limits are neither masked nor relaxed by #140.
