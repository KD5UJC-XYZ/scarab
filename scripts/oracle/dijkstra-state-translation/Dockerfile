# SPDX-License-Identifier: Apache-2.0
# Built on the Leios testnet oracle (scripts/oracle/leios-testnet), whose project already holds
# cardano-ledger-dijkstra and the other ledger packages at the revisions cardano-node b004fe0c pins.
FROM scarab-leios-testnet:local
WORKDIR /oracle/leios-testnet
COPY scripts/oracle/dijkstra-state-translation/Main.hs /oracle/dijkstra-state-translation/Main.hs
RUN cabal exec -- ghc -Wall -Werror -L/usr/local/lib -optl-Wl,-rpath,/usr/local/lib \
      -package cardano-ledger-dijkstra -package cardano-ledger-conway -package cardano-ledger-shelley \
      -package cardano-ledger-core -package cardano-ledger-binary -package mempack \
      -package microlens -package aeson -package bytestring -package containers -package text \
      /oracle/dijkstra-state-translation/Main.hs -o /usr/local/bin/scarab-dijkstra-state-translation
ENTRYPOINT ["/usr/local/bin/scarab-dijkstra-state-translation"]
