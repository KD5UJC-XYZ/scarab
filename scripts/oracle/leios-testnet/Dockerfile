# SPDX-License-Identifier: Apache-2.0
# The pinned base recipe is scripts/oracle/plutus/context/Dockerfile; it brings GHC, cabal and the
# native libraries. The ledger and cardano-base packages are built here from the revisions that
# cardano-node b004fe0c pins (cabal.project), not taken from the base image's store.
FROM scarab-plutus176-context-reference:local
WORKDIR /oracle/leios-testnet
COPY scripts/oracle/leios-testnet/cabal.project ./
COPY scripts/oracle/leios-testnet/scarab-leios-testnet-oracle.cabal harness/
RUN cabal update \
 && cabal build scarab-leios-testnet-oracle --only-dependencies --jobs=8 --extra-lib-dirs=/usr/local/lib
COPY scripts/oracle/leios-testnet/Main.hs harness/
RUN cabal build scarab-leios-testnet-oracle --jobs=8 --extra-lib-dirs=/usr/local/lib \
 && cp "$(cabal list-bin scarab-leios-testnet-oracle)" /usr/local/bin/scarab-leios-testnet-oracle
ENTRYPOINT ["/usr/local/bin/scarab-leios-testnet-oracle"]
