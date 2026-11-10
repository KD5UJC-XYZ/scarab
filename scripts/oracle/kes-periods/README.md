<!-- SPDX-License-Identifier: Apache-2.0 -->
# KES relative-period reference capture

This generator calls the unchanged Sum6KES implementation installed in the pinned Leios oracle
image. It emits public roots, messages and signatures with the original accept/reject observations;
it never emits signing material. The [fixture provenance](../../../fixtures/sum6/period-boundaries-provenance.json)
records the exact image, compiler, package, source identities and comparison scope.

From the repository root, with the [Leios reference image](../leios-testnet/README.md) available:

```sh
docker run --rm --network none --entrypoint bash \
  -v "$PWD/scripts/oracle/kes-periods:/probe:ro" scarab-leios-testnet:local -c \
  'cabal exec -- ghc -v0 -package cardano-crypto-class-2.6.0.0 -outputdir /tmp/kes-periods -o /tmp/kes-periods-bin /probe/Main.hs && /tmp/kes-periods-bin' \
  > /tmp/kes-period-boundaries.tsv
cmp fixtures/sum6/period-boundaries.tsv /tmp/kes-period-boundaries.tsv
```

The image's compiler environment is retained by using a non-login shell. Verify its image identity
against the provenance before comparing. A different build may retain the SingleKES assertion and
therefore differ on periods above 63; it must not silently replace this capture's expectations.
This is a native crypto predicate capture, not a full-node or modified-genesis experiment. Raw run
artifacts are not published.
