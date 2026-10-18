# SPDX-License-Identifier: Apache-2.0
FROM scarab-plutus176-context-reference:local
WORKDIR /oracle/context
COPY scripts/oracle/pre-conway-utxo/Main.hs /oracle/pre-conway-utxo/Main.hs
RUN cabal exec -- ghc -Wall -Werror -L/usr/local/lib -optl-Wl,-rpath,/usr/local/lib \
      -package cardano-ledger-alonzo -package cardano-ledger-babbage \
      -package cardano-ledger-shelley -package cardano-ledger-core -package cardano-ledger-conway \
      -package cardano-ledger-binary -package aeson -package validation-selective \
      -package microlens -package base16-bytestring \
      /oracle/pre-conway-utxo/Main.hs -o /usr/local/bin/scarab-pre-conway-utxo
ENTRYPOINT ["/usr/local/bin/scarab-pre-conway-utxo"]
