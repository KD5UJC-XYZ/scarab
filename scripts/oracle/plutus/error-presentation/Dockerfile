# SPDX-License-Identifier: Apache-2.0
FROM scarab-plutus-ctx-matrix:local
WORKDIR /oracle/context-matrix
COPY scripts/oracle/plutus/error-presentation/Main.hs scripts/oracle/plutus/error-presentation/scarab-context-oracle.cabal harness/
RUN cabal build scarab-context-oracle --offline --jobs=2 --extra-lib-dirs=/usr/local/lib \
 && cp "$(cabal list-bin scarab-context-oracle)" /usr/local/bin/scarab-error-presentation-oracle
ENTRYPOINT ["/usr/local/bin/scarab-error-presentation-oracle"]
