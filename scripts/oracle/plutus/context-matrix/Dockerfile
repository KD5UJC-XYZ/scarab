# SPDX-License-Identifier: Apache-2.0
FROM scarab-plutus176-context-reference:local
WORKDIR /oracle/context-matrix
COPY scripts/oracle/plutus/context-matrix/cabal.project scripts/oracle/plutus/context-matrix/cabal.project.freeze ./
COPY scripts/oracle/plutus/context-matrix/Main.hs scripts/oracle/plutus/context-matrix/scarab-context-oracle.cabal harness/
RUN cabal build scarab-context-oracle --offline --jobs=4 --extra-lib-dirs=/usr/local/lib \
 && cp "$(cabal list-bin scarab-context-oracle)" /usr/local/bin/scarab-context-matrix
ENTRYPOINT ["/usr/local/bin/scarab-context-matrix"]
