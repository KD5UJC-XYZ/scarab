# SPDX-License-Identifier: Apache-2.0
# Built on the Leios testnet oracle (scripts/oracle/leios-testnet), whose project already holds
# cardano-ledger-dijkstra and the other ledger packages at the revisions cardano-node b004fe0c pins.
FROM scarab-leios-testnet:local
WORKDIR /oracle/leios-testnet
COPY scripts/oracle/dijkstra-rules/Main.hs /oracle/dijkstra-rules/Main.hs
RUN cabal exec -- ghc -Wall -Werror -L/usr/local/lib -optl-Wl,-rpath,/usr/local/lib \
      -package cardano-ledger-dijkstra -package cardano-ledger-conway -package cardano-ledger-shelley \
      -package cardano-ledger-core -package cardano-ledger-binary -package cardano-binary \
      -package cardano-crypto-class -package cardano-protocol -package cardano-slotting -package cardano-data \
      -package small-steps -package mtl -package data-default -package cborg -package containers -package base \
      -package microlens -package aeson -package bytestring -package base16-bytestring -package text -package time \
      /oracle/dijkstra-rules/Main.hs -o /usr/local/bin/scarab-dijkstra-rules
ENTRYPOINT ["/usr/local/bin/scarab-dijkstra-rules"]
