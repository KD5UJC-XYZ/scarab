# SPDX-License-Identifier: Apache-2.0
# The parent image is the Leios testnet oracle (scripts/oracle/leios-testnet/Dockerfile), whose
# project already holds the ledger and cardano-base revisions of cardano-node b004fe0c; this oracle
# is a second package of that project, built against the same dependencies. The build is not
# --offline, as the Leios node-to-client query oracle's is not (scripts/oracle/leios-ntc-queries/Dockerfile).
# run.py measures the image it runs (the build files, the pinned checkouts, cabal's verdict and the
# executable) and does not trust this tag.
FROM scarab-leios-testnet:local
WORKDIR /oracle/leios-testnet
COPY scripts/oracle/dijkstra-epoch/scarab-dijkstra-epoch.cabal dijkstra-epoch/
COPY scripts/oracle/dijkstra-epoch/Main.hs dijkstra-epoch/
RUN printf 'packages: dijkstra-epoch\n' > cabal.project.local \
 && cabal build scarab-dijkstra-epoch --jobs=8 --extra-lib-dirs=/usr/local/lib \
 && cp "$(cabal list-bin scarab-dijkstra-epoch)" /usr/local/bin/scarab-dijkstra-epoch
ENTRYPOINT ["/usr/local/bin/scarab-dijkstra-epoch"]
