# SPDX-License-Identifier: Apache-2.0
FROM scarab-plutus176-context-reference:local
WORKDIR /oracle/historical-auxiliary
COPY scripts/oracle/plutus/historical-auxiliary/cabal.project scripts/oracle/plutus/historical-auxiliary/cabal.project.freeze ./
COPY scripts/oracle/plutus/historical-auxiliary/Main.hs scripts/oracle/plutus/historical-auxiliary/scarab-historical-auxiliary-oracle.cabal harness/
RUN cabal build scarab-historical-auxiliary-oracle --offline --jobs=2 --extra-lib-dirs=/usr/local/lib \
 && cp "$(cabal list-bin scarab-historical-auxiliary-oracle)" /usr/local/bin/scarab-historical-auxiliary-oracle
ENTRYPOINT ["/usr/local/bin/scarab-historical-auxiliary-oracle"]
