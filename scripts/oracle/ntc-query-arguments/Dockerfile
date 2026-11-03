# SPDX-License-Identifier: Apache-2.0
FROM scarab-plutus176-context-reference:local
WORKDIR /oracle/context
COPY scripts/oracle/ntc-query-arguments/Main.hs /oracle/ntc-query-arguments/Main.hs
RUN cabal exec -- ghc -Wall -Werror -L/usr/local/lib -optl-Wl,-rpath,/usr/local/lib \
      -package cardano-binary -package cardano-ledger-core -package cardano-ledger-binary \
      -package cardano-crypto-class -package aeson \
      -package base16-bytestring -package containers -package text -package bytestring \
      /oracle/ntc-query-arguments/Main.hs -o /usr/local/bin/scarab-ntc-query-arguments
ENTRYPOINT ["/usr/local/bin/scarab-ntc-query-arguments"]
