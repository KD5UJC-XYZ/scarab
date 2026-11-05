# ChainSync codec and selection oracle

**Status: Current** — optional offline reference evidence for
[#755](https://github.com/KD5UJC-XYZ/scarab/issues/755).

The observer executes the released `ouroboros-network` 1.2.0.0 codec, plain `runPeer` driver and
`AF.selectPoints`. Their source files are byte-identical to pin
`2fb69829a19b5c04e4fb212bdd78fb63f9d2d776`; the provenance records both SHA-256 and Git blob identities.
The point/hash decoder uses the unchanged `decodeRawHash`/`fromShortRawHash` excerpt at Cardano's
32-byte hash size from consensus `82ecba329d7d054340bf707d44fe6e9ac27cec40`. The consensus package
itself is not built.

Complete incoming messages run through native server/client peers on a fixed-input channel.
For FindIntersect, the harness appends native MsgDone so the server can terminate after its response.
This is the plain driver used by consensus's NtC server
(`ouroboros-consensus-diffusion/.../Network/NodeToClient.hs:448-452`), which has no codec byte/item/depth
budget. NtN's separate limited receive driver is not exercised; cases marked NtN fit its 65535-byte
bound. Direct outgoing cases invoke native codec encoding without a receive-size policy.

Incremental prefixes run the codec with one input chunk and no EOF, distinguishing `DecodePartial`
from refusal, including a wrong serialized-block tag or payload type before missing contents.
Opaque blocks use the native `Serialised` instance (tag 24 and a byte
string), preserving the block contents without interpreting them. Selection cases use the native
anchored fragment implementation plus consensus's exact `mkOffsets` body with its wrapper types
removed and the exact Double/Binet `fib` implementation (`Client.hs:2039-2043`, `Util.hs:443-449`).
The offsets-only Int.MaxValue case does not allocate a fragment of that size.

Compile and execute with GHC 9.10.3 and the existing offline package store:

```sh
out="$(mktemp -d "${SCARAB_DATA_DIR:-/tmp/scarab}/find-intersect-XXXXXX")"
cp scripts/oracle/chainsync-find-intersect/Main.hs scripts/oracle/chainsync-find-intersect/build.sh "$out"
docker run --rm --network none -v "$out:/observer" --entrypoint bash \
  scarab-33-genesis-reference-cache:local /observer/build.sh
python3 scripts/oracle/chainsync-find-intersect/find_intersect_cases.py \
  --inputs "$out/inputs.txt" --selection-inputs "$out/selection-inputs.txt"
docker run --rm -i --network none -v "$out:/observer" --entrypoint /observer/find-intersect-observer \
  scarab-33-genesis-reference-cache:local < "$out/inputs.txt" > "$out/reference.jsonl"
docker run --rm -i --network none -v "$out:/observer" --entrypoint /observer/find-intersect-observer \
  scarab-33-genesis-reference-cache:local < "$out/selection-inputs.txt" > "$out/selection.jsonl"
python3 scripts/oracle/chainsync-find-intersect/find_intersect_cases.py \
  --reference "$out/reference.jsonl" --out fixtures/reference/chainsync-find-intersect/cases.jsonl \
  --selection-reference "$out/selection.jsonl" --selection-out fixtures/reference/chainsync-find-intersect/selection.jsonl
```

The [fixture README](../../../fixtures/reference/chainsync-find-intersect/README.md) defines scope and
results. Bulk inputs, executables and build output are disposable after recording their identities;
they are not published.
