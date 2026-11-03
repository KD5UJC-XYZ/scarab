# ChainSync `MsgFindIntersect` as the server reads it

Status: Current. Evidence for [#755](https://github.com/KD5UJC-XYZ/scarab/issues/755).

The reference reads `MsgFindIntersect` (`[4, points]`) with `decodeList decodePoint`
(`Ouroboros/Network/Protocol/ChainSync/Codec.hs:165-166,209-214`, ouroboros-network 2fb69829):
a definite or indefinite list of any length, which only the codec's 65535 bytes a message bound
(`smallByteLimit`, `:47-51`). A point is `[]` or `[slot, hash]` (`decodePoint`, `Block.hs`), the slot
a `Word64` and the hash read by consensus's `decodeRawHash`, a definite byte string of exactly 32 bytes
(`Ouroboros/Consensus/Block/Abstract.hs:276-279,307-314`, ouroboros-consensus 82ecba32).

`cases.jsonl` holds 22 cases, written by
[`find_intersect_cases.py`](../../../scripts/oracle/chainsync-find-intersect/find_intersect_cases.py), each a
message as `prefix`, `count` copies of `unit` and `suffix` (a large one stays small here), with the
verdict of [the oracle](../../../scripts/oracle/chainsync-find-intersect/README.md) and, for an accepted
one, the number of points and the first:

- accepted: no points, one, the origin, 64, 65 (definite and indefinite), 1724 (the most 38-byte points
  65535 bytes hold), 9000 origins (more items than 8192) and 65000 origins in an indefinite list, a slot
  of `2^64 - 1`, a hash with a non-minimal length head;
- refused: a hash of 0, 31, 33 or 64 bytes ("decodeRawHash: hash has unexpected size"), a hash in two
  chunks, a point of one or three fields or as an indefinite list, a negative slot, points not a list,
  and the message as an indefinite list.

In all, 11 accepted and 11 refused. `ChainSyncFindIntersectReferenceSuite` requires
`ChainSync.decodePrefix` in `StIdle` to give each verdict, and for an accepted case to read the whole
message with the same number of points and the same first point. `provenance.json` records the
packages, the hashes of the sources against the pin, of the observer, the generator, its input and
output, and of the corpus.

## Not covered here

- The answer: which point the server finds is the chain's (`ChainSyncRuntimeSuite`); this corpus is the
  reading of the request.
- Node-to-client ChainSync, whose messages Scarab bounds at 2.5 MB where the reference has no codec
  limit.
