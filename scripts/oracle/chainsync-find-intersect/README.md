# ChainSync find-intersect oracle

**Status: Current** — optional offline reference oracle for
[#755](https://github.com/KD5UJC-XYZ/scarab/issues/755).

`Main.hs` reads node-to-node ChainSync client messages in the server's `StIdle` with the released
`codecChainSync` of `ouroboros-network` 1.2.0.0 (the version cardano-node 11.1.3 runs; its
`ChainSync/Codec.hs` and `Block.hs` are byte-identical to the pinned 2fb69829) and the point and tip
decoders consensus gives it, `decodePoint (decodeRawHash p)` (`Ouroboros/Consensus/Network/NodeToNode.hs`,
ouroboros-consensus 82ecba32). `decodeRawHash` and `fromShortRawHash` are an unchanged excerpt of
`Ouroboros/Consensus/Block/Abstract.hs:276-279,307-314` at the 32 bytes of every Cardano era's
`HashSize`; the consensus package itself is not built. It prints, for each hex message on its input,
the verdict, the complete failure, and the points read.

It is compiled with GHC 9.10.3 against the released packages in the store of
`scarab-33-genesis-reference-cache:local`, by `build.sh`'s command, with no network:

```sh
out="$(mktemp -d "${SCARAB_DATA_DIR:-/tmp/scarab}/find-intersect-XXXXXX")"
cp scripts/oracle/chainsync-find-intersect/Main.hs scripts/oracle/chainsync-find-intersect/build.sh "$out"
docker run --rm --network none -v "$out:/observer" --entrypoint bash \
  scarab-33-genesis-reference-cache:local /observer/build.sh
python3 scripts/oracle/chainsync-find-intersect/find_intersect_cases.py --inputs "$out/inputs.txt"
docker run --rm -i --network none -v "$out:/observer" --entrypoint /observer/find-intersect-observer \
  scarab-33-genesis-reference-cache:local < "$out/inputs.txt" > "$out/reference.jsonl"
python3 scripts/oracle/chainsync-find-intersect/find_intersect_cases.py --reference "$out/reference.jsonl" \
  --out fixtures/reference/chainsync-find-intersect/cases.jsonl
```

The cases are described in the [fixture README](../../../fixtures/reference/chainsync-find-intersect/README.md).
