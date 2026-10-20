# Pinned upstream typed-protocol codec cases

These are deterministic observations from the real `ouroboros-network:protocols-tests` properties
at commit `2fb69829a19b5c04e4fb212bdd78fb63f9d2d776`. The disposable observer overlay only adds
export entrypoints; it retains the upstream `AnyMessage` `Arbitrary` instances, codec functions,
and property bodies. `keepalive.tsv` is generated from `KeepAlive/Test.hs`'s `prop_codec_v2` and
`peersharing.tsv` from `PeerSharing/Test.hs`'s `prop_codec_PeerSharing`, `prop_codec_cbor`,
`prop_codec_valid_cbor`, `prop_codec_splits2`, and `prop_codec_splits3` on the same original generated
messages. The adjacent manifests
record source file hashes, exact seed/size values, dependency plan, overlay patch hash, upstream
property result, and raw fixture hash.

The KeepAlive and PeerSharing files each have 24 cases from seed `490201`; each row's seed is base
plus ordinal and size cycles from 30 through 50. The original property was run over each exported
message, and the filtered upstream test suites also passed: all nine KeepAlive properties and all
ten PeerSharing properties. The upstream suite's QuickCheck properties each passed 100 cases,
except the split-three properties, which each passed 33.

`keepalive-behavior.tsv` adds 24 rows from the unchanged pinned `prop_direct`, `prop_connect`, and
`prop_channel_ST` properties, using seeds `490901` through `490924`. Each row stores the exact finite
accumulator trace observed for the generated QuickCheck function, the original descending Word16
cookie sequence (`n` through `1`), and all three property verdicts. The network test replays those
cookies and Done over a real in-memory mux pair against Scarab's production `KeepAliveServer.run`,
checking each echoed value and the server's completion after Done. Scarab's automatic client's
incrementing-cookie policy is outside this adapter; this tests the upstream server-visible behavior
and typed message path without rewriting the generated sequence.

Unsupported rows below describe exact typed-adapter gaps for upstream test parameterizations; they
are not Cardano production-wire failures or demonstrated protocol divergences.

The pinned `cardano-diffusion` suite adds bounded Cardano-specific evidence.
`cardano-handshake.tsv` contains 40 deterministic cases from the unchanged
`prop_acceptable_symmetric_NodeToNode` property at v16; each retains both original proposal CBOR
messages and has explicit generator seed provenance. Scarab consumes all 40, with one negotiated
result, 19 query results, and 20 refusals. This is a bounded sample of one property, not the full
Cardano Handshake suite.


The separate `cardano-handshake-query.tsv` retains 64 independent original
`ArbitraryNodeToNodeVersions` pairs from `prop_query_version_NodeToNode_ST`. All 64
upstream property invocations pass, but the property intentionally treats handshake errors as
`True`: Scarab maps this deterministic sample to 62 network-magic refusals, two version-mismatch
refusals, and zero query replies. It therefore does not demonstrate query success. The separately
labeled `cardano-handshake-query-correlated.tsv` uses one unchanged generated version map as both
inputs to the same ST property; 63 nonempty maps produce actual typed `QueryReply` exchanges through
Scarab's client and responder, with complete version maps and data checked. One empty-map candidate
is retained as a precondition-filtered row. This correlated slice is reported separately from the
independent-pair property sample.

`cardano-handshake-accept-refuse.tsv` retains 64 more independently generated pairs from the
unchanged `prop_acceptOrRefuse_symmetric_NodeToNode`; all upstream property invocations pass. The
current sample exercises 62 symmetric refusals and two symmetric version-mismatch refusals in the
Scarab typed client/responder replay. It contains no negotiated success cases, so this bounded
property sample does not establish accepted-negotiation coverage. The separate
`cardano-handshake-accept-refuse-correlated.tsv` reuses one unchanged generated map on both sides
for each property invocation; 63 nonempty maps pass, producing 32 Negotiated results and 31 QueryReply
results through Scarab, while one empty map is recorded as a precondition-filtered VersionMismatch.
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
default values are inserted. The same generated rows now include unchanged upstream split-2 and
split-3 verdicts (24/24 pass each). Scarab's production prefix decoder is replayed over every
partition for the 18 mapped rows, with a reordered-fragment negative control; the six generic
integer-address rows retain the typed `DefiniteArray` boundary error and are not counted as Scarab
split matches.

This is bounded evidence for selected codec properties, not a claim that all protocol properties or
Ouroboros-network tests are covered. Other applicable protocols and typed-protocol behaviors remain
open under #49. Source licensing is Apache-2.0; see `fixtures/licenses/ouroboros-network-LICENSE`
and `fixtures/licenses/ouroboros-network-NOTICE`.

## Original mock-domain replay and strict Cardano projections

`UpstreamGenericCodecCoverageSuite` now executes every retained original ChainSync, BlockFetch,
LocalStateQuery, TxSubmission2 and PeerSharing codec row through the shared production generic
engines. This includes all base, split, binary-direction and separately seeded annotated rows:
752 total, with immutable fixture hashes and native verdicts. It verifies exact original bytes,
message fields independently extracted by the general CBOR parser, and every distinct prefix for
split observations. See [the row inventory](COVERAGE.md) for counts.
The typed compatibility counts below describe the **strict Cardano projection** only; its refusals
remain tested separately and no longer exclude the original mock rows from generic execution.

The ChainSync `codecSerialised` observer exports 128 rows from the real
`AnyMessage ChainSync_Serialised_BlockHeader` generator. Both unchanged
`prop_codec_ChainSyncSerialised` and `prop_codec_cbor_ChainSyncSerialised` pass 128/128.
Scarab's strict Cardano facade decodes and exact-wire re-encodes 49 rows. The other 79 retain their original bytes and pass
verdicts but are unsupported at `ExpectedDefiniteByteString`: the upstream mock uses
`HeaderHash Int`, while Scarab requires hash bytes. No integer-to-byte mapping is applied. The
adjacent `chainsync-splits.tsv` reuses all 128 rows for the unchanged serialized split2 and split3
properties; both pass 128/128. Scarab replays every partition through its production prefix decoder
for the 49 typed-compatible rows, including empty fragments and truncation controls. The 79 generic
integer-hash rows preserve their passing upstream split verdicts and remain unsupported; they are not
counted as strict Cardano split passes.

The same 24 KeepAlive rows are now also run through the unchanged upstream
`prop_codec_v2_splits2` and `prop_codec_v2_splits3` properties. Both pass 24/24; the Scala
consumer enumerates every 2- and 3-part partition from upstream `splits2`/`splits3`, feeds each
fragment to Scarab's production `KeepAlive.decodePrefix` with retained-prefix accumulation, and
checks `NeedMore` on incomplete prefixes and the exact typed message plus consumed byte count once
the full wire is present. Empty fragments at the beginning or end are included. Each row also has a
negative control that reorders two nonempty fragments and rejects any decoded result. Fixture
SHA-256 and observer/property provenance are in `keepalive-splits.tsv.manifest.json`.

The LocalTxSubmission and LocalTxMonitor split fixtures reuse the exact 24 generated messages from
their existing codec fixtures (seeds 493001 and 494001 respectively), not a second or filtered
generator. The unchanged upstream `prop_codec_splits2`/`prop_codec_splits3` and
`prop_codec_splitsM_LocalTxMonitor splits2/splits3` each pass 24/24. Scarab consumes every 2- and
3-part partition through the corresponding production `decodePrefix`, checks the retained original
wire, exact typed value/consumed length, permits early completion before trailing empty fragments,
and rejects a truncated-wire negative control. All 24 generated rows in each family map to a typed
Scarab constructor. The two manifests record source hash, replay offsets and literal upstream
property outcomes.

The pinned BlockFetch observer exports 24 seeded `AnyMessage (BlockFetch (Serialised Block)
(Point Block))` cases at seed base 490201. Both unchanged `prop_codec_BlockFetchSerialised` and
`prop_codec_cbor_BlockFetchSerialised` pass for all 24 rows. Scarab's `CardanoBlockFetch` typed
consumer exactly decodes and re-encodes 20 rows across `ClientDone`, `StartBatch`, `NoBlocks`, and
`Block`. Four `RequestRange` rows retain their original wire and upstream pass verdict but are
classified unsupported: the upstream mock point uses `HeaderHash Int`, encoded as an integer,
while Scarab's point codec requires byte-string hashes. No conversion or synthetic hash is used.
The upstream target dependency closure is 167 units; the minimal solver project was checked against
the full plan and matches every package/component, flag, source/Cabal hash, and dependency edge.
The adjacent `blockfetch-splits.tsv` reuses those exact rows for the unchanged
`prop_codec_splits2_BlockFetchSerialised` and `prop_codec_splits3_BlockFetchSerialised`
properties; each passed 24/24. Scarab replays every two- and three-part partition, including empty
segments, through its production prefix decoder for the 20 typed-compatible rows and checks a
truncated-wire negative control. The four mock-point rows preserve both passing upstream split
verdicts but remain unsupported at `ExpectedDefiniteByteString`; they are not counted as
strict Cardano split passes.

The LocalStateQuery observer exports 24 seeded stateful messages from the unchanged
`AnyMessageWithResult` generator. `prop_codec_LocalStateQuery` and `prop_codec_cbor` pass for all 24.
Scarab's strict Cardano facade exactly decodes and re-encodes 20 rows, including acquire/reacquire
tips, result, release, failure, and done messages. Four specific-point acquire/reacquire rows retain original wire and
upstream pass status but are unsupported at `ExpectedDefiniteByteString`: upstream mock
`HeaderHash Int` is integer-encoded, while Scarab's point type requires byte-string hashes. No
point conversion or fabricated value is used; query/result payloads remain opaque full CBOR items.
These are distinct point domains; generic production-engine replay covers every original row.
The separate `localsq-splits.tsv` fixture reuses these exact 24 rows and records the unchanged
`prop_codec_splits2` and `prop_codec_splits3` verdicts (24/24 each). Scarab runs every two- and
three-part partition through its production stateful prefix decoder for the 20 typed-compatible
rows, including trailing-empty-segment handling and a truncated-wire negative control. The four
specific-point mock rows retain their original wire and both passing upstream verdicts, but remain
unsupported by the typed adapter for the same `ExpectedDefiniteByteString` reason. Their upstream
passes are not counted as strict Cardano split passes.

The LocalTxSubmission observer exports 24 rows from the pinned upstream
`AnyMessage (LocalTxSubmission Tx Reject)` generator at seed base 493001. Both unchanged
`prop_codec` and `prop_codec_cbor` pass for all 24. Scarab's typed consumer decodes all 24,
checks the constructor and agency state, consumes the complete original wire, and re-encodes it
byte-for-byte. `Tx` and `Reject Int` payloads are retained as their complete original CBOR items.

The pinned LocalTxMonitor observer exports 24 messages from the unchanged
`AnyMessage (LocalTxMonitor TxId Tx SlotNo)` generator at seed base 494001. Both
`prop_codecM_LocalTxMonitor` and `prop_codec_cborM_LocalTxMonitor` pass 24/24. Scarab's typed
consumer matches and exact-wire re-encodes all rows across acquire/acquired, busy replies, size and
measure queries, transaction lookup, release, and await-acquire. Its split2/split3 results and
production prefix-decoder comparison are recorded in `localtxmonitor-splits.tsv`; codec-ID,
valid-CBOR, client/server, channel and pipe properties remain separate suite coverage.

The pinned TxSubmission2 `codec` and `codec cbor` observer exports 24 messages from the actual
`AnyMessage (TxSubmission2 TxId Tx)` generator at seed base 495001; both properties pass all 24.
Scarab exactly consumes and re-encodes 13 control/request rows. Three `ReplyIds`, four `RequestTxs`,
and four `ReplyTxs` rows remain unsupported at typed error `ExpectedDefiniteArray`: the generic
upstream test uses arbitrary CDDL `Any` values for `TxId` and `Tx`, while Scarab's Conway adapter
requires the Cardano transaction-id and GenTx structures. Original wires and upstream verdicts are
retained without conversion. This is a test-domain coverage gap, not a finding of invalid Cardano
wire or an upstream protocol defect. The adjacent `txsubmission2-splits.tsv` reuses these same 24
rows for unchanged `prop_codec_splits2` and `prop_codec_splits3`; each passes 24/24. Scarab replays
every partition through its production prefix decoder for the 13 typed-compatible rows, including
empty segments and a truncated-wire negative control. The 11 arbitrary CDDL transaction rows retain
the original wire and both passing upstream verdicts but remain unsupported at `ExpectedDefiniteArray`;
they are not counted as strict Cardano split passes. ID codec, annotated-codec, valid-CBOR and channel
properties remain separate coverage.

The pinned ChainSync `codecSerialised` observer now exports 128 rows from the actual
`AnyMessage ChainSync_Serialised_BlockHeader` generator, preserving each source state token,
sender agency, constructor, original CBOR wire, generator seed/size, and separate verdicts from
`prop_codec_ChainSyncSerialised` and `prop_codec_cbor_ChainSyncSerialised`. Both properties passed
128/128; the filtered upstream ChainSync test group passed all 34 tests (each QuickCheck property
reported 100 cases). The fixture manifest records the exact command logs, source hashes, observer
patch hash, and constructor/state counts. The Scala consumer passes the full sample, with 49 exact
typed matches and the same 79 `ExpectedDefiniteByteString` mock-hash cases listed above. This
the adjacent split fixture preserves the same 128 rows and property verdicts. Binary-compatibility,
valid-CBOR, pipelining and transport tests remain separate coverage.


The separate annotated fixtures use the pinned upstream `WithBytes Tx` generators and unchanged
`prop_anncodec`, `prop_anncodec_splits2`, and `prop_anncodec_splits3` functions. Each contains 24
rows with the complete annotated wire, source state, agency, constructor, and all three property
verdicts; seeds are 496001 for LocalTxSubmission and 497001 for TxSubmission2. All recorded
upstream properties pass on these rows. Their manifests preserve the source module and observer
overlay hashes and measured state/constructor counts. The Scala consumer is set to check exact
wire roundtrip and production prefix decoding for mapped rows; TxSubmission2 mock transaction payloads retain typed
domain errors and are not counted as matches. This proves full-wire preservation on mapped rows,
not equality of an in-memory annotation object.


A corrected exact-seed observer run added independent valid-CBOR outcomes to the existing
KeepAlive, PeerSharing, LocalStateQuery, LocalTxSubmission, LocalTxMonitor, and TxSubmission2 rows,
and codec-ID outcomes to LocalTxMonitor and TxSubmission2. Each newly recorded property passed on
all 24 same-seed cases. The raw wire/state/agency/constructor prefix of every existing ordinary row
was checked against the prior fixture before extending its columns. The six manifests record the
separate property counts and exact replay offsets. ChainSync remains 128 rows and the other families
remain 24; its serialized properties and all previously recorded split fixtures were regenerated
with byte-identical rows. These property results are upstream oracle results; Scarab's focused consumer
tests are reported separately from this manifest-level evidence.

The new `chainsync-binary.tsv` and `blockfetch-binary.tsv` fixtures retain both directions of
the pinned binary-compatibility properties: wrapped codec to `Serialised`, and `Serialised` back
to wrapped codec. They are generated from the original direction-specific `AnyMessage` domains,
with the existing ChainSync base seed/count (491101/128) and BlockFetch seed/count (490201/24).
Both original properties pass for every row in both directions. Paired rows retain identical wire
bytes, as required by those properties. Scarab matches 98/256 ChainSync directional rows and
40/48 BlockFetch directional rows. The remaining rows retain exact original wires and upstream
pass verdicts; ChainSync's mock `HeaderHash Int` points and BlockFetch's mock `Point Int` ranges
remain typed `ExpectedDefiniteByteString` unsupported cases. No mock integer is converted into a
Cardano hash. This observes the upstream binary-compatibility properties and the Scarab typed
boundary separately; it does not claim Cardano-instantiated ChainSync header/hash coverage.

The registered IO Pipe and Socket `prop_*_demo` properties share 24 original
`TestBlockChainAndUpdates` inputs (seeds 505001–505024). Each unchanged upstream property passes
on all 24 inputs. The Pipe fixtures retain all 517 updates, 587 final-chain blocks and the original
consumer stop ordinal, including three cases that stop before the producer's remaining tail.
`UpstreamPipeBehaviorSuite` checks those complete final chains and stop ordinals through the shared
production ChainSync runtime, using linked Mux bearers for Pipe and actual loopback TCP for Socket.
Server fibers return typed outcomes to the caller in their own error scope.

`chainsync-socket-demo-outcomes.tsv` binds each Socket verdict to the same Pipe seed, ordinal and
generator size. Its manifest pins the source, observer and dependency plan. The Socket adapter uses
Scarab's production Cardano Handshake (version 15); the upstream property uses its test-only
`TestVersion` handshake. These results compare generated chain behavior over TCP. They do not claim
handshake wire/version-data equality, OS-pipe scheduling equality or pipeline decision-trace parity.
