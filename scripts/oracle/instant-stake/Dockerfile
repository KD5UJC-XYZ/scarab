# SPDX-License-Identifier: Apache-2.0
FROM scarab-plutus176-context-reference:local
WORKDIR /oracle/context
COPY scripts/oracle/instant-stake/Main.hs /oracle/instant-stake/Main.hs
RUN cabal exec -- ghc -Wall -Werror -L/usr/local/lib -optl-Wl,-rpath,/usr/local/lib \
      -package cardano-ledger-shelley -package cardano-ledger-babbage -package cardano-ledger-core \
      -package cardano-crypto-class -package aeson -package base16-bytestring -package containers \
      -package text -package bytestring \
      /oracle/instant-stake/Main.hs -o /usr/local/bin/scarab-instant-stake
ENTRYPOINT ["/usr/local/bin/scarab-instant-stake"]
