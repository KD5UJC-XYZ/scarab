# SPDX-License-Identifier: Apache-2.0
FROM scarab-plutus176-context-reference:local
WORKDIR /oracle/context
COPY scripts/oracle/genesis-cost-models/Main.hs /oracle/genesis-cost-models/Main.hs
RUN cabal exec -- ghc -Wall -Werror -L/usr/local/lib -optl-Wl,-rpath,/usr/local/lib \
      -package cardano-ledger-alonzo -package cardano-ledger-conway \
      -package cardano-ledger-core -package aeson -package containers \
      -package bytestring \
      /oracle/genesis-cost-models/Main.hs -o /usr/local/bin/scarab-genesis-cost-models
ENTRYPOINT ["/usr/local/bin/scarab-genesis-cost-models"]
