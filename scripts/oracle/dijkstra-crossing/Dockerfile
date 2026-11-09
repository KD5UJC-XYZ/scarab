# SPDX-License-Identifier: Apache-2.0
# Built on the Leios testnet oracle (scripts/oracle/leios-testnet), whose project already holds
# cardano-ledger-dijkstra and the other ledger packages at the revisions cardano-node b004fe0c pins.
FROM scarab-leios-testnet:local
WORKDIR /oracle/leios-testnet
COPY scripts/oracle/dijkstra-crossing/Main.hs /oracle/dijkstra-crossing/Main.hs
RUN cabal exec -- ghc -Wall -Werror -L/usr/local/lib -optl-Wl,-rpath,/usr/local/lib \
      -package cardano-ledger-dijkstra -package cardano-ledger-conway -package cardano-ledger-alonzo \
      -package cardano-ledger-core -package cardano-ledger-binary -package cardano-crypto-class \
      -package microlens -package aeson -package bytestring -package text -package transformers \
      /oracle/dijkstra-crossing/Main.hs -o /usr/local/bin/scarab-dijkstra-crossing
ENTRYPOINT ["/usr/local/bin/scarab-dijkstra-crossing"]
