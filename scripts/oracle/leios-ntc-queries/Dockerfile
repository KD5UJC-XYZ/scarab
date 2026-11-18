# SPDX-License-Identifier: Apache-2.0
# The parent image is the Leios testnet oracle (scripts/oracle/leios-testnet/Dockerfile), whose
# project already holds the ledger and cardano-base revisions of cardano-node b004fe0c; this oracle
# is a second package of that project, built against the same dependencies (cardano-ledger-api, which
# the project's harness does not use, is built here from the same pinned checkout). The build is not
# --offline, as the chain harness's is not: offline, cabal refused to build cardano-ledger-api, whose
# plan entry is that source-repository package, as a download (Cabal-7125). run.py measures the image it runs (the build files, the pinned
# checkouts, cabal's verdict and the executable) and does not trust this tag.
FROM scarab-leios-testnet:local
WORKDIR /oracle/leios-testnet
COPY scripts/oracle/leios-ntc-queries/scarab-leios-ntc-queries.cabal leios-ntc-queries/
COPY scripts/oracle/leios-ntc-queries/Main.hs leios-ntc-queries/
RUN printf 'packages: leios-ntc-queries\n' > cabal.project.local \
 && cabal build scarab-leios-ntc-queries --jobs=8 --extra-lib-dirs=/usr/local/lib \
 && cp "$(cabal list-bin scarab-leios-ntc-queries)" /usr/local/bin/scarab-leios-ntc-queries
ENTRYPOINT ["/usr/local/bin/scarab-leios-ntc-queries"]
