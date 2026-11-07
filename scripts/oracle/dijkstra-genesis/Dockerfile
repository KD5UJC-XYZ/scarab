# SPDX-License-Identifier: Apache-2.0
# Built on the Leios testnet oracle (scripts/oracle/leios-testnet), whose project already holds
# cardano-ledger-dijkstra and aeson at the revisions cardano-node b004fe0c pins.
FROM scarab-leios-testnet:local
WORKDIR /oracle/leios-testnet
COPY scripts/oracle/dijkstra-genesis/Main.hs /oracle/dijkstra-genesis/Main.hs
RUN cabal exec -- ghc -Wall -Werror -L/usr/local/lib -optl-Wl,-rpath,/usr/local/lib \
      -package cardano-ledger-dijkstra -package aeson -package bytestring \
      /oracle/dijkstra-genesis/Main.hs -o /usr/local/bin/scarab-dijkstra-genesis
ENTRYPOINT ["/usr/local/bin/scarab-dijkstra-genesis"]
