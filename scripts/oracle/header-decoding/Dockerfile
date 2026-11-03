# SPDX-License-Identifier: Apache-2.0
# The pinned base recipe is scripts/oracle/plutus/context/Dockerfile; its store already holds the
# ledger packages, so this image builds only cardano-protocol, cardano-protocol-tpraos and the harness.
FROM scarab-plutus176-context-reference:local
WORKDIR /oracle/header
COPY scripts/oracle/header-decoding/cabal.project scripts/oracle/header-decoding/cabal.project.freeze ./
COPY scripts/oracle/header-decoding/Main.hs scripts/oracle/header-decoding/scarab-header-oracle.cabal harness/
RUN cabal build scarab-header-oracle --jobs=4 --extra-lib-dirs=/usr/local/lib \
 && cp "$(cabal list-bin scarab-header-oracle)" /usr/local/bin/scarab-header-oracle
ENTRYPOINT ["/usr/local/bin/scarab-header-oracle"]
