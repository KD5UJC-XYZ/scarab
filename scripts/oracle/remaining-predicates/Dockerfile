# SPDX-License-Identifier: Apache-2.0
FROM scarab-pre-conway178-oracle:local
WORKDIR /oracle/context
COPY scripts/oracle/remaining-predicates/Pool.hs scripts/oracle/remaining-predicates/Outputs.hs /oracle/remaining-predicates/
RUN cabal exec -- ghc -Wall -Werror -L/usr/local/lib -optl-Wl,-rpath,/usr/local/lib \
      -package cardano-ledger-conway -package cardano-ledger-shelley \
      -package cardano-ledger-core -package cardano-ledger-binary -package cardano-slotting \
      -package aeson -package mtl -package microlens -package base16-bytestring \
      -outputdir /oracle/remaining-predicates/pool-build \
      /oracle/remaining-predicates/Pool.hs -o /usr/local/bin/scarab-pool-predicates \
 && cabal exec -- ghc -Wall -Werror -L/usr/local/lib -optl-Wl,-rpath,/usr/local/lib \
      -package cardano-ledger-shelley -package cardano-ledger-allegra -package cardano-ledger-mary \
      -package cardano-ledger-core -package cardano-ledger-binary -package aeson \
      -package microlens -package validation-selective -package base16-bytestring \
      -outputdir /oracle/remaining-predicates/output-build \
      /oracle/remaining-predicates/Outputs.hs -o /usr/local/bin/scarab-output-predicates
ENTRYPOINT ["/usr/local/bin/scarab-pool-predicates"]
