# SPDX-License-Identifier: Apache-2.0
# Reuses the complete crypto build; only the request budget in the harness changes.
FROM scarab-plutus175-oracle:local
COPY scripts/oracle/plutus/costing/Main.hs /source/scarab-oracle/Main.hs
RUN sed -i "s/base, aeson/base, containers, aeson/" /source/scarab-oracle/scarab-plutus-oracle.cabal
RUN test "$(git rev-parse HEAD)" = "507cab8cf6192e5143f12be5b2d3005a2928dc6d" \
 && git diff --exit-code -- plutus-core plutus-ledger-api plutus-tx \
 && cabal build scarab-plutus-oracle --offline --jobs=2 \
 && cp "$(cabal list-bin scarab-plutus-oracle)" /usr/local/bin/scarab-plutus-oracle
