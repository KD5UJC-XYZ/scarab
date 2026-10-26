# Native scalar text observations

`Main.hs` calls the unmodified `Data.Text.pack`, strict UTF-8 decoder, Plutus `conText` parser and
`TextCostedByByteLength` instance in the exact image recorded in
[`fixtures/reference/plutus-text/manifest.json`](../../../../fixtures/reference/plutus-text/manifest.json).
The image's Cabal plan identifies `text-2.1.3`; its Plutus source is commit
`507cab8cf6192e5143f12be5b2d3005a2928dc6d`.

Run from the repository root after preparing that original reference image:

```sh
docker run --rm --cpus 2 --memory 4g --network none \
  -v "$PWD/scripts/oracle/plutus/text/Main.hs:/tmp/TextMain.hs:ro" \
  --entrypoint bash <recorded-image-id> \
  -c 'cabal exec --offline -v0 -- runghc -package=plutus-core -package=megaparsec -package=aeson /tmp/TextMain.hs'
```

The output is JSONL: all twelve constructor/byte-length cases, five strict UTF-8 cases and nine
literal-parser cases. Record every row without expected failures or field masks. These are value,
representation and parser observations; they do not certify CEK budget settlement or demand-driven
costing, which remain part of [#493](https://github.com/KD5UJC-XYZ/scarab/pull/493).
