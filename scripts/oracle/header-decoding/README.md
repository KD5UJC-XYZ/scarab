# Block header decoding oracle

**Status: Current** — optional offline reference oracle for
[#698](https://github.com/KD5UJC-XYZ/scarab/issues/698).

The harness calls the released header decoders of `cardano-protocol` 0.1.0.0 and
`cardano-protocol-tpraos` 1.6.0.0 (the versions of the pinned ledger checkout, whose header sources
they match) on original encodings: a TPraos `BHeader` (Shelley to Alonzo), a Praos `Header`
(Babbage, Conway) or a Leios `Header`, its header body alone, or a ledger `Block` holding the
header, each at its era's decoder version (Leios at Dijkstra's 12). It records the verdict and the
complete failure, and for an accepted case the header hash, a block's body hash and the header
body's `getSignableRepresentation`, the bytes the KES signature is checked over. No signature or
ledger rule is checked on a header. A "vrfcheck" case (#712) instead makes a Praos VRF key of a
seed, evaluates its certificate of a message with `cardano-crypto-praos` 2.2.4.0, and reports for
outputs of other lengths whether the certificate decodes and whether `verifyCertified` accepts it. The
[fixtures](../../../fixtures/reference/header-decoding/README.md) retain the original encodings, the
verdicts, and the source and package pins.

The image starts from the [context-reference image](../plutus/context/README.md), whose store holds
the ledger packages; build that first, then:

```sh
docker build -f scripts/oracle/header-decoding/Dockerfile -t scarab-header-decoding:local .
```

`cabal.project.freeze` is the context image's plan with the two protocol packages and
`cardano-ledger-dijkstra` added (`cabal freeze` in the built image). The cases come from
`header_cases.py`, run as the fixture README shows.
