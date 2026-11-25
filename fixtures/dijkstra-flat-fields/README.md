# Dijkstra transaction fields as the flat decoder read them

Status: Current — the expected values of `DijkstraFieldDigestSuite` (#85).

Before Scarab deleted its flat Dijkstra block decoder, which the Leios build's decoding was mirrored
by first and Dijkstra's era types were then projected from, every Dijkstra transaction the
repository holds was read that way once more and the fields its readings gave were recorded here:
the outputs, the certificates, the proposals and the metadata of each transaction, at its top level
and in each sub-transaction. The era decoder that replaced it reads each transaction in one pass,
and its readings must give the same fields.

`fields.jsonl` holds one line per distinct transaction the flat decoder accepted (911), sorted by
id: `id`, the first 16 hex digits of the transaction's id; and `o`, `c`, `p`, `m`, the digests of
its outputs, certificates, proposals and metadata: the first 12 hex digits of the SHA-256 of the
field's canonical form at every level (`DijkstraOnePassExpected.canonical`), or `-` where the field
is empty at every level. 80 transactions hold certificates, 83 proposals and 206 metadata.

The transactions are those `DijkstraOnePassSuite` reads: the public Leios testnet's blocks and the
endorser block closure of slot 1,455,869 (`fixtures/leios/testnet`), the devnet's chain and its
submitted transaction (`fixtures/dijkstra-devnet`), the Leios build's corpus of verdicts and its
blocks (`fixtures/reference/leios-testnet-blocks`), the script-context fixtures and their refusals,
the reference's UTXOW, ENTITIES and BBODY cases (`fixtures/reference/dijkstra-rules`) and the
crossing corpus's mempool forms (`fixtures/reference/dijkstra-crossing`).

Recorded on 2026-11-25 at Scarab commit `e0ffa09b1`, the last with the flat decoder and its
projection, by a test that read each transaction with `DijkstraTx.inBlockByFlat` (a block form) or
`DijkstraTx.fromSubmissionByFlat` (a mempool form) and wrote `DijkstraFieldDigests.of` of each.
The test and its throwaway checkout were removed after; the digests are Scarab's own, not the
reference's: they hold the era decoder to the readings it replaced. No secret is held.
