# SPDX-License-Identifier: Apache-2.0
# The pinned native-crypto/Plutus base recipe is scripts/oracle/byron/full/Dockerfile.
FROM scarab-byron171-full-reference:local
WORKDIR /oracle/context
COPY scripts/oracle/plutus/context/cabal.project scripts/oracle/plutus/context/cabal.project.freeze ./
COPY scripts/oracle/plutus/context/Main.hs scripts/oracle/plutus/context/scarab-context-oracle.cabal harness/
RUN cabal build scarab-context-oracle --jobs=4 --extra-lib-dirs=/usr/local/lib \
 && cp "$(cabal list-bin scarab-context-oracle)" /usr/local/bin/scarab-context-oracle
ENTRYPOINT ["/usr/local/bin/scarab-context-oracle"]
