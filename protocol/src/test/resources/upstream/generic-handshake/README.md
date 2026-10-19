# Generic Handshake observations

Pinned `ouroboros-network` `2fb69829a19b5c04e4fb212bdd78fb63f9d2d776`, original
`Ouroboros/Network/Protocol/Handshake/Test.hs`. These are its actual mock versions
0/1/2, not renamed Cardano 14/15/16. `GenericHandshakeUpstreamSuite` consumes the
production bounded Handshake parser/wire codec and highest-common-version selector.
The Cardano facades retain their independent closed version and data policies.

| Corpus | Original inputs and upstream observations | Native comparison |
|---|---|---|
| `codec.tsv` | 48 original `AnyMessage` draws; codec, split-2, split-3, CBOR and valid-CBOR properties each pass 48/48 | Exact original bytes round trip; every two/three partition, including empty chunks, feeds the production prefix decoder; coalesced suffix preserved |
| `refuse.tsv` | 24 original `ArbitraryRefuseReason` draws; standalone codec passes 24/24 | Exact diagnostic UTF-8 and original version-list ordering/duplicates retained |
| `behavior.tsv` | 48 original `ArbitraryVersions` pairs; ten original connect/channel/pipe/asymmetric/simultaneous-open properties each pass 48/48 | Both native selection results and the version-1-only asymmetric server match independent upstream `acceptOrRefuse` observations; 17 ordinary accepts, 31 refusals, one asymmetric accept and 47 asymmetric refusals |
| `probes.tsv` | Nine controlled cross-version probes, data drawn with the original per-version generator and evaluated by original `acceptOrRefuse`; explicit judgments rather than registered-property passes | Three valid diagonal selections and six exact decode refusals; version-2 probes include a valid lower version and never fall back |
| `symmetry.tsv` | 24 original data pairs and valid-version-map pairs; both original symmetry properties pass 24/24 | Native agreement and bidirectional highest-common selection satisfy the same contracts |

Each manifest gives exact TSV columns, seeds, sizes, upstream source/observer hashes,
executable/image/project/plan hashes, command results and per-property outcomes.
Data maps retain both original structured values and original version-specific wire
projections: version 0 drops both booleans and version 1 drops its final boolean.
An invalid generated association can therefore decode successfully with different data;
the adapter preserves the original local value when comparing the observed result.

The asymmetric server has original data `(0,True,True)` at version 1. It skips
unrecognized version-0/2 map entries using the production wire decoder. It does not
replace mock data with Cardano features or silently treat a refusal as a pass.

The original ST/IO/pipe/SimNet verdicts are separate observations. Scarab compares the
shared pure wire/selection contract; it does not claim a new generic effectful
Handshake runtime, OS pipe, SimNet scheduling or interpreter equivalence. The three
QuickCheck generator-consistency leaves concern upstream generation/shrinking, rather
than an additional production protocol. Samples are bounded, and stricter native
resource/refusal-arity limits are unchanged. Remaining K2 coverage is tracked by
[#49](https://github.com/KD5UJC-XYZ/scarab/issues/49).

Regenerate from a clean pinned source checkout, using a private work directory outside
the repository:

```sh
python3 scripts/oracle/network-protocols/generic-handshake/export.py \
  --source /path/to/ouroboros-network \
  --work /tmp/scarab-generic-handshake-oracle \
  --output protocol/src/test/resources/upstream/generic-handshake
```

The retained observer changes only the upstream test module: a disposable registered
export leaf calls original generators, peers, codecs and properties without replacing
their implementations. The selected build's 167-unit target dependency closure is
independently compared with a fresh solve of the complete original `cabal.project`;
`dependency-closure.json` retains that result. This is not a comparison of a plan with
itself. A clean second exporter run reproduced every TSV byte. The retained patch later had
one blank context prefix normalized for repository whitespace checks; manifests retain
both the executed and retained patch hashes. Independently applying the normalized
patch reconstructs the identical recorded executed-source hash.

The native adapter uses a closed `Version` enum for the original 0/1/2 values.
Contextual `VersionCodec[Version]`, `Ordering[Version]` and
`DataCodec[Version, Data, DataError]` instances are shared with the generic production
wire/selection operations. Typed wire messages and refusals cannot accidentally use
Cardano's version codec. `TypedHandshakeSuite` checks missing/mismatched codec rejection
at compile time, node-to-client bit-15 framing and existing facade error precedence.
