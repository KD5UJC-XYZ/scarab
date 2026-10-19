# SPDX-License-Identifier: Apache-2.0
FROM scarab-plutus176-context-reference:local
WORKDIR /oracle/aggregation
COPY scripts/oracle/plutus/aggregation/cabal.project scripts/oracle/plutus/aggregation/cabal.project.freeze ./
COPY scripts/oracle/plutus/aggregation/Main.hs scripts/oracle/plutus/aggregation/scarab-aggregation-oracle.cabal harness/
RUN cabal build scarab-aggregation-oracle --offline --jobs=4 --extra-lib-dirs=/usr/local/lib \
 && cp "$(cabal list-bin scarab-aggregation-oracle)" /usr/local/bin/scarab-aggregation-oracle
ENTRYPOINT ["/usr/local/bin/scarab-aggregation-oracle"]
