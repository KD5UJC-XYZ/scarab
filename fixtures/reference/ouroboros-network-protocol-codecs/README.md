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

`UpstreamProtocolCodecCoverageSuite` consumes the original encoded bytes at Scarab's typed codec
boundary. KeepAlive matches all 24 rows. PeerSharing matches its 18 request/done rows. Its six
actual `MsgSharePeers` rows are retained with upstream pass status and `unsupported-int-addresses`:
the upstream property instantiates peer payloads as `Int`, while Scarab's protocol codec requires
typed IPv4/IPv6 addresses, so there is no lossless field mapping. No synthetic peer addresses or
default values are inserted.

This is bounded evidence for two codec properties, not a claim that all protocol properties or
Ouroboros-network tests are covered. Other applicable protocols and typed-protocol behaviors remain
open under #49. Source licensing is Apache-2.0; see `fixtures/licenses/ouroboros-network-LICENSE`
and `fixtures/licenses/ouroboros-network-NOTICE`.
