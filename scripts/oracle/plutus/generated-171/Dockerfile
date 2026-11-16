# SPDX-License-Identifier: Apache-2.0
# The generated-program harness on the parent oracle's build of plutus 1.71.0.0
# (scripts/oracle/plutus/Dockerfile with PLUTUS_COMMIT=261766ad...); only the harness changes.
FROM scarab-plutus171-oracle:local
COPY scripts/oracle/plutus/generated-171/Main.hs /source/scarab-oracle/Main.hs
RUN sed -i "s/base, aeson/base, containers, serialise, aeson/" /source/scarab-oracle/scarab-plutus-oracle.cabal
RUN test "$(git rev-parse HEAD)" = "261766ad1d582bce8c86bad1af7a3b0e5355b21c" \
 && git diff --exit-code -- plutus-core plutus-ledger-api plutus-tx \
 && cabal build scarab-plutus-oracle --offline --jobs=2 \
 && cp "$(cabal list-bin scarab-plutus-oracle)" /usr/local/bin/scarab-plutus-oracle
