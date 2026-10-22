# Network fixtures

Byte-exact mini-protocol and multiplexer messages for the codecs in `network/` and for the generated models
(`models`). One hex-encoded message per line (several lines in a file where a file holds a family of
messages). The top-level message files and older source-derived corpora are **specified or derived from
sources, not recorded from a running node**. The separate `chainsync-pipeline/` corpus executes native
policy functions with explicit inputs; it is not a live connection capture. Interoperability is shown
by the Preview sync and devnet scenarios, with precise scope in `docs/compatibility.md`.

| File | Contents |
|---|---|
| `ntn-accept14-preview.hex`, `ntc-propose16-preview.hex`, `ntc-query23-preview.hex`, `ntc-queryreply23-preview.hex`, `ntc-accept16-mainnet.hex` | Handshake messages of the Cardano node-to-node (version 14) and node-to-client (versions 16 and 23) protocols, manually specified from the CDDL and the Haskell codec, with the network magic of Preview (2) or mainnet (764824073) in the version data. The names say which magic. |
| `scalus-ntn-propose14.hex`, `scalus-ntn-accept16.hex`, `scalus-ntn-refuse.hex` | Node-to-node handshake messages (propose with version 14, accept with version 16, refuse) derived from the Scalus node (Apache-2.0, licence in `../licenses/scalus-node-LICENSE`; the `scalus-` prefix names the source). |
| `mux-ntn-propose14.hex`, `mux-ntn-accept14.hex` | The same handshake messages inside a multiplexer segment (8-byte header: timestamp, protocol 0, direction, length). |
| `chainsync-pipeline/` | [798 actual pinned native low/high decision observations](chainsync-pipeline/README.md), including stateful policy continuations, Origin and Word64 boundary inputs; these are explicit observations, not original upstream generated scenarios. |
| `block-fetch/` | BlockFetch messages (request range, start batch, no blocks, batch done, client done, a toy block and a Conway block wrapped in the node-to-node envelope) with `manifest.json`: SHA-256 of every file, the pinned upstream commits and the Haskell sources the encoders were derived from (`sources/`). Each file's `kind` field says whether it is a synthetic encoder literal, an envelope around an upstream golden, or a retained source. |
| `keepalive-direct-range/` | The KeepAlive v2 sources (Haskell `Type`, `Codec`, `Client`, `Server`, `Limits`, `KeepAlive`) at the pinned ouroboros-network commit, with `manifest.json`, `SHA256SUMS` and the Apache-2.0 licence: the specification the pure KeepAlive codec and its bounded runtime follow. No message bytes. |
| `tcp-direct-range/` | The node-to-node version and KeepAlive sources the strict NtN 14 profile was written against, with `manifest.json` and `SHA256SUMS`. No message bytes. |

The sources are from IntersectMBO/ouroboros-network at commit `c45735a56c567fa977969173d18943bac6bb3821`
(KeepAlive pin) and IntersectMBO/ouroboros-consensus at `82ecba329d7d054340bf707d44fe6e9ac27cec40`
(BlockFetch consensus side). The older source-derived corpora record `"upstreamRuntimeExecuted": false` and
`"externalEndpointExecutedHere": false`; that description does not apply to the separately identified
executed native decision corpus, whose manifest records its actual build and command.

Used by `BlockFetchGoldenSuite` (`network`), `NetworkGoldenSuite` (`models`: the handshake files decode like
the hand-written codec and re-encode to the same bytes) and as seeds of the fuzzers (`fuzz`).

## Provenance and licence

The retained Haskell sources and the golden-derived envelopes are Apache-2.0 works of Input Output Global and
contributors; their licence and NOTICE texts are kept unchanged in `../licenses/` (`ouroboros-network-LICENSE`,
`block-fetch-network-*`, `block-fetch-consensus-*`, `cardano-diffusion-NOTICE`, `network-mux-NOTICE`,
`ouroboros-framework-NOTICE`) and beside the manifests (`keepalive-direct-range/LICENSE-Apache-2.0`). The Scalus
vectors are Apache-2.0 (`../licenses/scalus-node-LICENSE`). The message files specified by hand from the CDDL
carry no third-party content and fall under the repository's Apache-2.0. No private data, key or secret is
included. See `../../NOTICE` and `../../docs/licenses.md`.
