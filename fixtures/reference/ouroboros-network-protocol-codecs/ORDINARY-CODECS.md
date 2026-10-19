# Ordinary inline-payload codec observations

Status: bounded adapter evidence for [#49](https://github.com/KD5UJC-XYZ/scarab/issues/49).

`blockfetch-ordinary.tsv` and `chainsync-ordinary.tsv` each retain 64 original
`AnyMessage` samples from `ouroboros-network`
`2fb69829a19b5c04e4fb212bdd78fb63f9d2d776`. Their domains are the actual mock
`Block` and `BlockHeader`, with integer header hashes. No Cardano identifier is
fabricated. Seeds are 509101–509164 and 510101–510164, respectively; generator size
is `8 + ordinal mod 9`. Every constructor is present in both bounded samples.

These are the ordinary `codec` families, whose block/header payloads are inline
CBOR. They are distinct from `codecWrapped` (tag-24 wrapping) and `codecSerialised`,
already observed by the separate binary-compatibility fixtures. The observer adds
entrypoints to the original modules without changing their generators, codecs or
property bodies. Each original input is passed to all five original properties:

| Family | Unchanged property functions | Observed verdicts |
|---|---|---|
| BlockFetch | `prop_codec_BlockFetch`, `prop_codec_splits2_BlockFetch`, `prop_codec_splits3_BlockFetch`, `prop_codec_cbor_BlockFetch`, `prop_codec_valid_cbor_BlockFetch` | 64 passes each |
| ChainSync | `prop_codec_ChainSync`, `prop_codec_splits2_ChainSync`, `prop_codec_splits3_ChainSync`, `prop_codec_cbor`, `prop_codec_valid_cbor` | 64 passes each |

Each row records the complete original wire, source state and sender, constructor,
separately encoded original fields, original Haskell message rendering and all five
verdicts. The manifests identify the observer patch, original/patched modules,
actual Docker image and executable, original source pin, project and dependency
plan, command exit statuses and log hashes. The selected target's 167-unit
dependency closure matches the previously retained full reference plan. This is
target-closure equivalence, not equality of the whole project's solver plan.

`UpstreamOrdinaryCodecSuite` constructs the expected messages from the separately
exported fields, compares their complete structural CBOR values, and exercises the
same production `BlockFetchEngine`/`ChainSyncEngine` framing used by the Cardano
facades. Payload callbacks call production `Cbor.decode` and `Cbor.encode`; they are
not byte identity functions. Mock header arrays, signed integer hashes and block
body byte strings remain unchanged. Every two- and three-part partition is replayed,
including empty segments. Negative controls check truncation, reordered bytes,
wrong agency and terminal states; a suffix checks the exact consumed length.

This establishes generic protocol framing with structural payload equality. It
does not establish Cardano payload decoding, independent equivalence of the mock
payload's Haskell `Serialise` implementation, Haskell ST/IO interpreter equivalence,
pipeline scheduling or whole-family coverage. No production behavior or persisted
native format changes. Other families remain under #49.

Regenerate outside the checkout, with Docker reference dependencies already cached:

```sh
python3 scripts/oracle/network-protocols/ordinary_codecs.py \
  --source /path/to/pinned/ouroboros-network \
  --probe-dir /tmp/scarab/ordinary-codecs-fresh \
  --output-dir fixtures/reference/ouroboros-network-protocol-codecs \
  --reference-plan /path/to/full-reference-plan.json
```

`--image` selects a prepared GHC 9.10.3/Cabal reference image; the manifest records
its actual digest. Optional `--cache-build` copies an existing compatible Cabal
build into the private probe. The runner uses one offline build job, a private
container and source archive, and removes its container even on failure. Copy the
resulting four corpus/manifest files to the corresponding protocol test-resource
directory. Runtime logs and private build artifacts stay under the probe directory.
