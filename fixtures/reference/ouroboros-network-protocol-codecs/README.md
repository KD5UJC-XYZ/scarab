# Pinned upstream typed-protocol codec cases

These are deterministic observations from the real `ouroboros-network:protocols-tests` properties
at commit `2fb69829a19b5c04e4fb212bdd78fb63f9d2d776`. The disposable observer overlay only adds
export entrypoints; it retains the upstream `AnyMessage` `Arbitrary` instances, codec functions,
and property bodies. `keepalive.tsv` is generated from `KeepAlive/Test.hs`'s `prop_codec_v2` and
`peersharing.tsv` from `PeerSharing/Test.hs`'s `prop_codec_PeerSharing`. The adjacent manifests
record source file hashes, exact seed/size values, dependency plan, overlay patch hash, upstream
property result, and raw fixture hash.

Each file has 24 cases from seed `490201`; the row seed is base plus ordinal and size cycles from
30 through 50. The original property was run over each exported message, and the filtered upstream
test suites also passed: all nine KeepAlive properties and all ten PeerSharing properties. The
upstream suite's QuickCheck properties each passed 100 cases, except the split-three properties,
which each passed 33.

The pinned `cardano-diffusion` suite adds bounded Cardano-specific evidence.
`cardano-handshake.tsv` contains 40 deterministic cases from the unchanged
`prop_acceptable_symmetric_NodeToNode` property at v16; each retains both original proposal CBOR
messages and has explicit generator seed provenance. Scarab consumes all 40, with one negotiated
result, 19 query results, and 20 refusals. This is a bounded sample of one property, not the full
Cardano Handshake suite.

`cardano-peersharing-sockaddr.tsv` contains 300 real CDDL-generated messages: 100 each from the
pinned `unit_decodePeerSharingV14ToLast` test for NodeToNode v14, v15, and v16. The upstream
`unit_encodePeerSharingV14ToLast` SockAddr property also passes 100 generated cases. The CDDL
generator does not expose a reproducible seed, so the fixture records its version and ordinal and
keeps the original diagnostic term and wire bytes rather than inventing seed provenance. Scarab
decodes all 300 to typed values and its own encodings decode to the same values. A replay of all
300 original and Scarab-produced wires through the pinned version-specific Haskell codec also
compared equal typed message renderings, including complete `SockAddr` lists. Peer-list arrays can
be encoded definite upstream and indefinite by Scarab, so this adapter checks typed-value parity
and retains the source bytes; it does not claim byte-for-byte re-encoding parity.

Each Cardano fixture has a manifest with source SHA-256, observer patch hash, result counts, and
limitations. The observer patches add only export hooks around existing generator/property and
decoder paths.

`UpstreamProtocolCodecCoverageSuite` consumes the original encoded bytes at Scarab's typed codec
boundary. KeepAlive matches all 24 rows. PeerSharing matches its 18 request/done rows. Its six
actual `MsgSharePeers` rows are retained with upstream pass status and `unsupported-int-addresses`:
the upstream property instantiates peer payloads as `Int`, while Scarab's protocol codec requires
typed IPv4/IPv6 addresses, so there is no lossless field mapping. No synthetic peer addresses or
default values are inserted.

This is bounded evidence for selected codec properties, not a claim that all protocol properties or
Ouroboros-network tests are covered. Other applicable protocols and typed-protocol behaviors remain
open under #49. Source licensing is Apache-2.0; see `fixtures/licenses/ouroboros-network-LICENSE`
and `fixtures/licenses/ouroboros-network-NOTICE`.

The ChainSync `codecSerialised` observer exports 128 rows from the real
`AnyMessage ChainSync_Serialised_BlockHeader` generator. Both unchanged
`prop_codec_ChainSyncSerialised` and `prop_codec_cbor_ChainSyncSerialised` pass 128/128. Scarab
decodes and exact-wire re-encodes 49 rows. The other 79 retain their original bytes and pass
verdicts but are unsupported at `ExpectedDefiniteByteString`: the upstream mock uses
`HeaderHash Int`, while Scarab requires hash bytes. No integer-to-byte mapping is applied.

The pinned BlockFetch observer exports 24 seeded `AnyMessage (BlockFetch (Serialised Block)
(Point Block))` cases at seed base 490201. Both unchanged `prop_codec_BlockFetchSerialised` and
`prop_codec_cbor_BlockFetchSerialised` pass for all 24 rows. Scarab's `CardanoBlockFetch` typed
consumer exactly decodes and re-encodes 20 rows across `ClientDone`, `StartBatch`, `NoBlocks`, and
`Block`. Four `RequestRange` rows retain their original wire and upstream pass verdict but are
classified unsupported: the upstream mock point uses `HeaderHash Int`, encoded as an integer,
while Scarab's point codec requires byte-string hashes. No conversion or synthetic hash is used.
The upstream target dependency closure is 167 units; the minimal solver project was checked against
the full plan and matches every package/component, flag, source/Cabal hash, and dependency edge.

The LocalStateQuery observer exports 24 seeded stateful messages from the unchanged
`AnyMessageWithResult` generator. `prop_codec_LocalStateQuery` and `prop_codec_cbor` pass for all 24.
Scarab exactly decodes and re-encodes 20 rows, including acquire/reacquire tips, result, release,
failure, and done messages. Four specific-point acquire/reacquire rows retain original wire and
upstream pass status but are unsupported at `ExpectedDefiniteByteString`: upstream mock
`HeaderHash Int` is integer-encoded, while Scarab's point type requires byte-string hashes. No
point conversion or fabricated value is used; query/result payloads remain opaque full CBOR items.

The pinned ChainSync `codecSerialised` observer now exports 128 rows from the actual
`AnyMessage ChainSync_Serialised_BlockHeader` generator, preserving each source state token,
sender agency, constructor, original CBOR wire, generator seed/size, and separate verdicts from
`prop_codec_ChainSyncSerialised` and `prop_codec_cbor_ChainSyncSerialised`. Both properties passed
128/128; the filtered upstream ChainSync test group passed all 34 tests (each QuickCheck property
reported 100 cases). The fixture manifest records the exact command logs, source hashes, observer
patch hash, and constructor/state counts. The Scala consumer is set up to verify typed
decode/encode parity for these rows; the focused consumer result is pending. This bounded sample
does not imply coverage of ChainSync split, binary-compatibility, valid-CBOR, pipelining or
transport tests.
