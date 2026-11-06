# SPDX-License-Identifier: Apache-2.0
# Reuse the pinned Plutus 1.70 / ledger 1.21 project and dependency build.
FROM scarab-plutus-ctx-matrix:local
COPY scripts/oracle/plutus/diagnostics/Main.hs /oracle/context-matrix/harness/Main.hs
RUN sed -i 's/build-depends: /build-depends: prettyprinter, serialise, plutus-tx, /' harness/scarab-context-oracle.cabal \
 && cabal build scarab-context-oracle --offline --jobs=4 --extra-lib-dirs=/usr/local/lib \
 && cp "$(cabal list-bin scarab-context-oracle)" /usr/local/bin/scarab-diagnostics-oracle
ENTRYPOINT ["/usr/local/bin/scarab-diagnostics-oracle"]
