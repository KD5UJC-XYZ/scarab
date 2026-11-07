# SPDX-License-Identifier: Apache-2.0
# Built on the Leios testnet oracle (scripts/oracle/leios-testnet), whose project already holds
# cardano-ledger-dijkstra and the other ledger packages at the revisions cardano-node b004fe0c pins.
FROM scarab-leios-testnet:local
WORKDIR /oracle/leios-testnet
COPY scripts/oracle/dijkstra-translation/Main.hs /oracle/dijkstra-translation/Main.hs
RUN cabal exec -- ghc -Wall -Werror -L/usr/local/lib -optl-Wl,-rpath,/usr/local/lib \
      -package cardano-ledger-dijkstra -package cardano-ledger-conway -package cardano-ledger-babbage \
      -package cardano-ledger-core -package cardano-ledger-binary -package cardano-crypto-class \
      -package microlens -package aeson -package bytestring -package text \
      /oracle/dijkstra-translation/Main.hs -o /usr/local/bin/scarab-dijkstra-translation
ENTRYPOINT ["/usr/local/bin/scarab-dijkstra-translation"]
