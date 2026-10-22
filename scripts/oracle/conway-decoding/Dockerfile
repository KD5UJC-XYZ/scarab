# SPDX-License-Identifier: Apache-2.0
FROM scarab-plutus176-context-reference:local
WORKDIR /oracle/context
COPY scripts/oracle/conway-decoding/Main.hs /oracle/conway-decoding/Main.hs
RUN cabal exec -- ghc -Wall -Werror -L/usr/local/lib -optl-Wl,-rpath,/usr/local/lib \
      -package cardano-ledger-babbage -package cardano-ledger-core -package cardano-ledger-conway \
      -package cardano-ledger-binary -package aeson -package base16-bytestring \
      /oracle/conway-decoding/Main.hs -o /usr/local/bin/scarab-conway-decoding
ENTRYPOINT ["/usr/local/bin/scarab-conway-decoding"]
