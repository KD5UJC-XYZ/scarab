# Era decoders: certificate and native script tags, a block body's auxiliary data and invalid transactions

Status: Current.

Neighbouring eras read some parts of a block with different decoders. At cardano-ledger
`f649f9751074d2ab3de033fc3912f29c9862c1f5`:
- a certificate of Shelley to Babbage is a `ShelleyTxCert`, whose decoder takes tags 0 to 6 and
  calls `invalidKey` for any other (`eras/shelley/impl/src/Cardano/Ledger/Shelley/TxCert.hs:475-487`);
  Conway's `ConwayTxCert` takes 0 to 4 and 7 to 18 and fails 5 (genesis delegation) and 6 (moving
  instantaneous rewards) as no longer supported (`eras/conway/impl/src/Cardano/Ledger/Conway/TxCert.hs:674-719`);
- a native script of a Shelley witness set is a `MultiSig`, tags 0 to 3
  (`eras/shelley/impl/src/Cardano/Ledger/Shelley/Scripts.hs:264-278`); from Allegra it is a
  `Timelock`, which adds the time locks 4 and 5 (`eras/allegra/impl/src/Cardano/Ledger/Allegra/Scripts.hs:235-245`);
- a block body's auxiliary data map is read by `decodeIntMap`
  (`libs/cardano-ledger-binary/src/Cardano/Ledger/Binary/Decoding/Decoder.hs:858-878`), which refuses
  a map that names a transaction index twice from decoder version 9 (Conway) and keeps the last
  entry below it;
- a block body's invalid transactions, Alonzo to Conway, become validity flags through
  `alignedValidFlags` (`eras/alonzo/impl/src/Cardano/Ledger/Alonzo/BlockBody/Internal.hs:278-286`),
  which takes `Seq.replicate (index - previous - 1)` for each index. For a repeated or a decreasing
  index the count is negative and `Data.Sequence.replicate` (containers 0.7) calls `error`: the
  reference throws while it decodes the block body, where for other faults it returns a decoder
  failure.

## Files

- `inputs/`: the files the harness read. `batch.json` holds 97 cases, read in one process. Each
  invalid-transaction list that repeats an index or decreases ends the process with the exception,
  so each of the nine is in a file of its own (`single-00.json` to `single-08.json`).
  `controls-expected.json` holds the committed verdicts of three lines of the
  [detached records corpus](../recd-detached/README.md) replayed in the batch as controls.
- `cases.jsonl`: the reference verdicts, a line per case (106) with its `name`, `era`, `kind`
  (`body`, `witnesses`, or `blockbody`, the parts of a block after its header), `accepted`, the
  complete `failure`, and the `bytes` read. 30 are accepted and 76 refused. The lines of the batch
  are the harness output (`decodeFullAnnotator` at the era's decoder version); each of the nine
  single cases exited with status 1 and no output, and its `failure` is `reference throws: `
  followed by the first line of its standard error (`oracle` is the name the harness was compiled
  to).
- `provenance.json`: the pins and the hashes of the harness, the generator, the inputs, the output
  and the cases.

The cases are written by `scripts/oracle/conway-decoding/era_decoder_cases.py`, which writes
`inputs/` and reads the harness output into `cases.jsonl`, both byte for byte as committed:
- a body of Shelley to Babbage with a certificate of tag 0 and of each tag 7 to 18, and a Conway body
  with a certificate of tag 5, 6 and 7;
- a Shelley and an Allegra witness set with a native script of tag 0, 4 and 5, and an all-of that
  holds a tag 5;
- a Mary to Conway block body whose auxiliary data map names index 0 once, twice, or 1 then 0;
- an Alonzo to Conway block body of two transactions whose invalid list is `[0,1]`, `[1]`, `[1,0]`,
  `[0,0]` or `[1,1]`.

`EraDecodersReferenceSuite` (ledger) runs each case through Scarab's decoders: `TxBody.decode`,
`Witnesses.decode`, or `Block.decodeShelley` of a block built around the block body and
`Tx.fromBlock` of each of its transactions. It requires the reference's verdict, and for a refusal
the Scarab error of the reference's rule.

Scope: wire decoding only; no ledger rule, signature or script is run.

## Provenance

- Harness: `scripts/oracle/conway-decoding/Main.hs` (sha256 in `provenance.json`), unchanged.
- Image: `scarab-conway-decoding:local`, image ID
  `sha256:3ecb0be74930140cd001a929612a0b348f2ffab96dc8c29b571ca8d36a34dc44`, built 2026-10-28 from
  `scripts/oracle/conway-decoding/Dockerfile` on the [context reference image](../../../scripts/oracle/plutus/context/README.md).
  Its own binary predates the harness's block-body kind, so no image was built: the harness was
  compiled inside a container of that image with GHC 9.10.3 and the Dockerfile's `ghc` command and
  packages (without `-Werror`), and run there with `--network none`.
- Packages (`scripts/oracle/plutus/context/cabal.project.freeze`): cardano-ledger-binary 1.9.1.0,
  -core 1.21.0.0, -shelley 1.19.0.1, -allegra 1.10.1.0, -mary 1.11.0.0, -alonzo 1.16.0.0, -babbage
  1.14.0.0, -conway 1.23.0.0, cborg 0.2.10.0, containers 0.7: the ledger of cardano-node 11.1.3.
- Run: 2026-11-13.

## Regenerating

Build `scarab-conway-decoding:local` as [the oracle README](../../../scripts/oracle/conway-decoding/README.md)
describes, from the current `Main.hs`. Then:

```sh
python3 scripts/oracle/conway-decoding/era_decoder_cases.py --inputs /tmp/era-decoders/inputs
mkdir -p /tmp/era-decoders/out
for f in /tmp/era-decoders/inputs/batch.json /tmp/era-decoders/inputs/single-*.json; do
  name=$(basename "$f" .json)
  docker run --rm --network none --read-only -v /tmp/era-decoders/inputs:/fixtures:ro \
    scarab-conway-decoding:local "/fixtures/$name.json" \
    > "/tmp/era-decoders/out/$name.json" 2> "/tmp/era-decoders/out/$name.stderr"
done
python3 scripts/oracle/conway-decoding/era_decoder_cases.py --inputs /tmp/era-decoders/inputs \
  --outputs /tmp/era-decoders/out --out /tmp/era-decoders/cases.jsonl
# the nine single cases differ in one word: the image's binary is named scarab-conway-decoding, and
# a harness prints its own name before the exception (the committed lines say "oracle")
diff /tmp/era-decoders/cases.jsonl fixtures/reference/era-decoders/cases.jsonl
```

This recipe was not run as written: the committed verdicts come from the harness compiled inside a
container of the image (Provenance above), and the generator was checked to write the committed
`inputs/` and, from that run's output, `cases.jsonl` byte for byte.

## Not covered here

- How the running node handles the exception `alignedValidFlags` throws: the harness shows only that
  decoding the block body throws.
- Ledger rules: a refused encoding is refused before any rule runs.
