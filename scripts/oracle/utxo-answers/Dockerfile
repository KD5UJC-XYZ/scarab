# SPDX-License-Identifier: Apache-2.0
FROM scarab-plutus176-context-reference:local
WORKDIR /oracle/context
COPY scripts/oracle/utxo-answers/Main.hs /oracle/utxo-answers/Main.hs
RUN cabal exec -- ghc -Wall -Werror -L/usr/local/lib -optl-Wl,-rpath,/usr/local/lib \
      -package cardano-ledger-shelley -package cardano-ledger-allegra -package cardano-ledger-mary \
      -package cardano-ledger-alonzo -package cardano-ledger-babbage -package cardano-ledger-conway \
      -package cardano-ledger-core -package cardano-ledger-binary -package cborg -package aeson \
      -package base16-bytestring -package containers -package text -package bytestring \
      /oracle/utxo-answers/Main.hs -o /usr/local/bin/scarab-utxo-answers
ENTRYPOINT ["/usr/local/bin/scarab-utxo-answers"]
