# SPDX-License-Identifier: Apache-2.0
# The parent image is the Leios testnet oracle (scripts/oracle/leios-testnet/Dockerfile), whose
# project already holds the ledger and cardano-base revisions of cardano-node b004fe0c; this oracle
# is a second package of that project, built against the same dependencies. run.py measures the
# image it runs (the build files, the pinned checkouts, cabal's verdict and the executable) and does
# not trust this tag.
FROM scarab-leios-testnet:local
WORKDIR /oracle/leios-testnet
COPY scripts/oracle/dijkstra-state-translation/scarab-dijkstra-state-translation.cabal dijkstra-state-translation/
COPY scripts/oracle/dijkstra-state-translation/Main.hs dijkstra-state-translation/
RUN printf 'packages: dijkstra-state-translation\n' > cabal.project.local \
 && cabal build scarab-dijkstra-state-translation --offline --jobs=8 --extra-lib-dirs=/usr/local/lib \
 && cp "$(cabal list-bin scarab-dijkstra-state-translation)" /usr/local/bin/scarab-dijkstra-state-translation
ENTRYPOINT ["/usr/local/bin/scarab-dijkstra-state-translation"]
