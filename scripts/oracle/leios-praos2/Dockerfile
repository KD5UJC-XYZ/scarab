# SPDX-License-Identifier: Apache-2.0
# Reuse the pinned ledger/crypto dependencies built by ../leios-testnet/Dockerfile.
ARG LEIOS_REFERENCE_IMAGE=scarab-leios-testnet-reference:local
FROM ${LEIOS_REFERENCE_IMAGE} AS dependencies
RUN apt-get update && apt-get install -y --no-install-recommends liburing-dev \
 && rm -rf /var/lib/apt/lists/*
WORKDIR /oracle/leios-praos2
COPY scripts/oracle/leios-praos2/cabal.project ./
COPY scripts/oracle/leios-praos2/scarab-leios-praos2-oracle.cabal harness/
RUN cabal build scarab-leios-praos2-oracle --only-dependencies --jobs=8 --extra-lib-dirs=/usr/local/lib

FROM dependencies AS oracle
COPY scripts/oracle/leios-praos2/Main.hs harness/
RUN cabal build scarab-leios-praos2-oracle --jobs=8 --extra-lib-dirs=/usr/local/lib \
 && cp "$(cabal list-bin scarab-leios-praos2-oracle)" /usr/local/bin/scarab-leios-praos2-oracle
ENTRYPOINT ["/usr/local/bin/scarab-leios-praos2-oracle"]
