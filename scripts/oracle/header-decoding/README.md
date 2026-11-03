# Block header decoding oracle

**Status: Current** — optional offline reference oracle for
[#698](https://github.com/KD5UJC-XYZ/scarab/issues/698).

The harness calls the released header decoders of `cardano-protocol` 0.1.0.0 and
`cardano-protocol-tpraos` 1.6.0.0 (the versions of the pinned ledger checkout, whose header sources
they match) on original encodings: a TPraos `BHeader` (Shelley to Alonzo), a Praos `Header`
(Babbage, Conway) or a Leios `Header`, its header body alone, or a ledger `Block` holding the
header, each at its era's decoder version (Leios at Dijkstra's 12). The generator no longer writes
Leios cases: the Leios reference is the build of the Leios testnet, and
[its oracle](../leios-testnet/README.md) has them (#711). It records the verdict and the
complete failure, and for an accepted case the header hash, a block's body hash and the header
body's `getSignableRepresentation`, the bytes the KES signature is checked over. No signature or
ledger rule is checked on a header. A "vrfcheck" case (#712) instead makes a Praos VRF key of a
seed, evaluates its certificate of a message with `cardano-crypto-praos` 2.2.4.0, and reports for
outputs of other lengths whether the certificate decodes and whether `verifyCertified` accepts it. A
"boundaryheader" case of era "byron" reads a Byron epoch boundary header with `decCBORABoundaryHeader`
of `cardano-ledger-byron` 1.3.0.0 at `byronProtVer`, as consensus reads it from ChainSync, and reports
its verdict only (#749). A "mainheader" case instead calls `decCBORAHeader` with 21,600 epoch
slots at the same version; it also records the decode verdict only, including a header with an
SSC proof larger than Scarab's former 4096-byte limit. The
[fixtures](../../../fixtures/reference/header-decoding/README.md) retain the original encodings, the
verdicts, and the source and package pins.

The image starts from the [context-reference image](../plutus/context/README.md), whose store holds
the ledger packages; build that first, then:

```sh
docker build -f scripts/oracle/header-decoding/Dockerfile -t scarab-header-decoding:local .
```

`cabal.project.freeze` is the context image's plan with the two protocol packages and
`cardano-ledger-dijkstra` added (`cabal freeze` in the built image); `cardano-ledger-byron` is in it
already. The cases come from
`header_cases.py`, run as the fixture README shows.
