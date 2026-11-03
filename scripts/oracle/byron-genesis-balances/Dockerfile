# SPDX-License-Identifier: Apache-2.0
FROM scarab-plutus176-context-reference:local
WORKDIR /oracle/context
COPY scripts/oracle/byron-genesis-balances/Main.hs /oracle/byron-genesis-balances/Main.hs
RUN cabal exec -- ghc -Wall -Werror -L/usr/local/lib -optl-Wl,-rpath,/usr/local/lib \
      -package cardano-ledger-byron -package cardano-crypto-wrapper -package cardano-ledger-binary \
      -package cardano-prelude -package canonical-json -package aeson -package base16-bytestring \
      -package bytestring -package containers -package text \
      /oracle/byron-genesis-balances/Main.hs -o /usr/local/bin/scarab-byron-genesis-balances
ENTRYPOINT ["/usr/local/bin/scarab-byron-genesis-balances"]
