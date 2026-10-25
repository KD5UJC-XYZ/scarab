# SPDX-License-Identifier: Apache-2.0
# GHC 9.10.3 on Debian bookworm-slim: tag haskell:9.10.3-slim-bookworm, index digest checked on 2026-10-24.
FROM haskell@sha256:3c9f47467dbd6cb62c6ec6b768270c627bc33565e209bece103b7484075b27e3
RUN apt-get update \
 && apt-get install -y --no-install-recommends git ca-certificates pkg-config libsodium-dev libsecp256k1-dev \
 && rm -rf /var/lib/apt/lists/*
ARG BLST_COMMIT=6d960cd05d6fe2b5bc9ba161edf0c1a131b87c4c
RUN git init -q /tmp/blst \
 && cd /tmp/blst \
 && git fetch -q --depth 1 https://github.com/supranational/blst.git ${BLST_COMMIT} \
 && git checkout -q FETCH_HEAD \
 && test "$(git rev-parse HEAD)" = "${BLST_COMMIT}" \
 && ./build.sh -D__BLST_PORTABLE__ \
 && cp libblst.a /usr/local/lib/ \
 && cp bindings/blst.h bindings/blst_aux.h /usr/local/include/ \
 && mkdir -p /usr/local/lib/pkgconfig \
 && printf '%s\n' 'prefix=/usr/local' 'libdir=/usr/local/lib' 'includedir=/usr/local/include' \
      'Name: libblst' 'Description: pinned portable blst' 'Version: 0.3.15' \
      'Libs: -L/usr/local/lib -lblst' 'Cflags: -I/usr/local/include' \
      > /usr/local/lib/pkgconfig/libblst.pc
ARG PLUTUS_COMMIT=507cab8cf6192e5143f12be5b2d3005a2928dc6d
RUN git init -q /source \
 && cd /source \
 && git fetch -q --depth 1 https://github.com/IntersectMBO/plutus.git ${PLUTUS_COMMIT} \
 && git checkout -q FETCH_HEAD \
 && test "$(git rev-parse HEAD)" = "${PLUTUS_COMMIT}"
WORKDIR /source
COPY scripts/oracle/plutus/Main.hs scripts/oracle/plutus/scarab-plutus-oracle.cabal scarab-oracle/
RUN awk '/^packages:/ {exit} {print}' cabal.project > cabal.project.header \
 && mv cabal.project.header cabal.project \
 && printf '%s\n' 'packages: plutus-core plutus-ledger-api plutus-tx scarab-oracle' \
      'write-ghc-environment-files: never' 'tests: false' 'benchmarks: false' 'optimization: 0' \
      'package plutus-core' '  flags: -with-inline-r -with-cert +with-crypto' \
      'package bitvec' '  flags: -simd' >> cabal.project \
 && cabal user-config init \
 && sed -i 's|url: http://hackage.haskell.org/|url: https://hackage.haskell.org/|' /root/.config/cabal/config \
 && cabal update \
 && cabal build scarab-plutus-oracle --jobs=6 \
 && cp "$(cabal list-bin scarab-plutus-oracle)" /usr/local/bin/scarab-plutus-oracle
ENTRYPOINT ["/usr/local/bin/scarab-plutus-oracle"]
