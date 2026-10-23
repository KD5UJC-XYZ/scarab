# SPDX-License-Identifier: Apache-2.0
FROM scarab-plutus176-context-reference:local
WORKDIR /oracle/context
COPY scripts/oracle/cost-models/Main.hs /oracle/cost-models/Main.hs
RUN cabal exec -- ghc -Wall -Werror -L/usr/local/lib -optl-Wl,-rpath,/usr/local/lib \
      -package cardano-ledger-alonzo -package cardano-ledger-babbage \
      -package cardano-ledger-shelley -package cardano-ledger-core \
      -package cardano-ledger-binary -package aeson -package containers \
      -package microlens -package base16-bytestring -package text -package bytestring \
      /oracle/cost-models/Main.hs -o /usr/local/bin/scarab-cost-models
ENTRYPOINT ["/usr/local/bin/scarab-cost-models"]
