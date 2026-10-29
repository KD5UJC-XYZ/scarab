# SPDX-License-Identifier: Apache-2.0
FROM scarab-plutus176-context-reference:local
WORKDIR /oracle/context
COPY scripts/oracle/genesis-fields/Main.hs /oracle/genesis-fields/Main.hs
RUN cabal exec -- ghc -Wall -Werror -L/usr/local/lib -optl-Wl,-rpath,/usr/local/lib \
      -package cardano-ledger-shelley -package cardano-ledger-alonzo \
      -package cardano-ledger-conway -package aeson -package bytestring \
      /oracle/genesis-fields/Main.hs -o /usr/local/bin/scarab-genesis-fields
ENTRYPOINT ["/usr/local/bin/scarab-genesis-fields"]
