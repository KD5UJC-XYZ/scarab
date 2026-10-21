# SPDX-License-Identifier: Apache-2.0
FROM scarab-plutus176-context-reference:local
WORKDIR /oracle/auxiliary
COPY scripts/oracle/plutus/auxiliary/cabal.project scripts/oracle/plutus/auxiliary/cabal.project.freeze ./
COPY scripts/oracle/plutus/auxiliary/Main.hs scripts/oracle/plutus/auxiliary/scarab-auxiliary-oracle.cabal harness/
RUN cabal build scarab-auxiliary-oracle --offline --jobs=2 --extra-lib-dirs=/usr/local/lib \
 && cp "$(cabal list-bin scarab-auxiliary-oracle)" /usr/local/bin/scarab-auxiliary-oracle
ENTRYPOINT ["/usr/local/bin/scarab-auxiliary-oracle"]
