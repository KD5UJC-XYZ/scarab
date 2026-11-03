# SPDX-License-Identifier: Apache-2.0
FROM scarab-plutus176-context-reference:local
WORKDIR /oracle/context
COPY scripts/oracle/byron-update-state/Main.hs /oracle/byron-update-state/Main.hs
RUN cabal exec -- ghc -Wall -Werror -L/usr/local/lib -optl-Wl,-rpath,/usr/local/lib \
      -package cardano-ledger-byron -package cardano-crypto-wrapper -package cardano-ledger-binary \
      -package cborg -package aeson -package base16-bytestring -package bytestring -package containers -package text \
      /oracle/byron-update-state/Main.hs -o /usr/local/bin/scarab-byron-update-state
ENTRYPOINT ["/usr/local/bin/scarab-byron-update-state"]
