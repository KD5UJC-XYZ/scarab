# SPDX-License-Identifier: Apache-2.0
FROM scarab-plutus176-context-reference:local
WORKDIR /oracle/context
COPY scripts/oracle/rewards/Main.hs /oracle/rewards/Main.hs
RUN cabal exec -- ghc -Wall -Werror -L/usr/local/lib -optl-Wl,-rpath,/usr/local/lib \
      -package cardano-ledger-shelley -package cardano-ledger-babbage -package cardano-ledger-conway \
      -package cardano-ledger-core -package cardano-ledger-binary -package cardano-slotting \
      -package aeson -package base16-bytestring -package mtl -package small-steps \
      /oracle/rewards/Main.hs -o /usr/local/bin/scarab-reward-oracle
ENTRYPOINT ["/usr/local/bin/scarab-reward-oracle"]
