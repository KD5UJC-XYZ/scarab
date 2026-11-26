# SPDX-License-Identifier: Apache-2.0
# The reference side of the closure benchmark (#1380), from the Leios oracle image for its GHC, cabal
# and native libraries; its own project builds the ledger packages with optimisation (`cabal.project`).
FROM scarab-leios-testnet:local
WORKDIR /oracle/leios-closure-bench
COPY scripts/oracle/leios-testnet/bench/cabal.project ./
COPY scripts/oracle/leios-testnet/bench/scarab-leios-closure-bench.cabal harness/
RUN cabal update \
 && cabal build scarab-leios-closure-bench --only-dependencies --jobs=12 --extra-lib-dirs=/usr/local/lib
COPY scripts/oracle/leios-testnet/bench/Main.hs harness/
RUN cabal build scarab-leios-closure-bench --jobs=12 --extra-lib-dirs=/usr/local/lib \
 && cp "$(cabal list-bin scarab-leios-closure-bench)" /usr/local/bin/scarab-leios-closure-bench
ENTRYPOINT ["/usr/local/bin/scarab-leios-closure-bench"]
