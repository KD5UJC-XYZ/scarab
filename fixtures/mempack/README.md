# MemPack `TxOut` goldens (S1 of docs/design/haskell-snapshots.md)

`preprod-conway-sample.tsv`: 95 ledger outputs, in the bytes the Haskell node wrote, picked from the
UTxO table of a real preprod ledger snapshot. One output per combination of constructor (tags 0 to 5),
address kind (base with key or script credentials, enterprise, pointer, Byron), ada-only or multi-asset,
datum (none, hash, inline) and reference script (native, Plutus V1, V2, V3) that the table holds, and a
few outputs picked for the shape of the multi-asset value (two or more policies, one asset name under two
policies, the empty name with others, 24 or more policies or assets, a quantity of 2^32 or more). Outputs
larger than 400 bytes (3,000 for the 24-policy values) were skipped.

One line per output, tab separated:

| Field | Content |
|---|---|
| class | what the output was picked for |
| key | the 34 bytes of the table key, hex (32 bytes of transaction id, then the index as a big-endian `Word16`) |
| value | the MemPack `TxOut`, hex: the bytes of the `tables` file between the byte-string head and the next key |
| projection | JSON of what the independent Python decoder `scripts/oracle/mempack.py` reads from the value (address, lovelace, assets, datum, script hash) |

## Provenance

Mithril `cardano-database` of preprod, epoch 319, immutable file number 6320, created 2026-10-17, signed
entity hash `7a0f984a0bd31301a5afba23abfd9aa4261dc62d8689752c60ac6384759bbfe4`, cardano-node 11.1.3. The
ancillary archive (440 MB) at
`https://storage.googleapis.com/cdn.aggregator.release-preprod.api.mithril.network/cardano-database/ancillary/preprod-e319-i6320.ancillary.tar.zst`
holds `ledger/136511997/{state,tables,meta}`; the `tables` file is 1,014,553,955 bytes with 4,526,058
outputs (CRC32 1172950672; the `meta` checksum 2668385107 is `CRC32` of the decimal CRC32s of `state` and
`tables`, see the design document). The SHA-256 of the files, from `ancillary_manifest.json`:

```
ledger/136511997/meta    b27a7d1ae9d5b9494d8d39048947a99436f6c44329af8bc35ea4e37c7a8a418c
ledger/136511997/state   7ecbc47624c3e6f6059520dc31f230971e77da8bd9ac29e894cdeaefb2002e96
ledger/136511997/tables  62ee546d9cf0c47f95444c213fb1bd55509a29a3f08cf8a9e9afa22bbaa5c2ed
```

The archive was downloaded and unpacked on 2026-10-17; the manifest signature was not verified (only the
public chain data of the ledger is used). The sample was picked by a one-off script that streams the file
with `scripts/oracle/mempack.py` (`read_tables`, `tx_out`). Preprod is public network data; there is nothing
private in the outputs.

## Why these and not bytes built by a Haskell program

Building `cardano-ledger` and its dependencies for a one-off program needs hours and the package index;
the node that wrote these bytes is the reference this repository is checked against (ADR 0001), and 4.5
million outputs of it exist to compare with. The devnet snapshots of `fixtures/haskell-state` add the
earlier eras (Shelley to Babbage) from the same node version.
