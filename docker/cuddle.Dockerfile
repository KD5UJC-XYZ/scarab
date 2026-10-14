# SPDX-License-Identifier: Apache-2.0
# cuddle (upstream's Haskell CDDL tool), built from Hackage with a pinned compiler and a pinned package index.
# Used only by scripts/cddl_crosscheck.py (docs/models.md, section 13); never part of the sbt build.
#
#   docker build -f docker/cuddle.Dockerfile -t scarab-cuddle:1.8.1.0 docker
#
# Both base images are fixed by digest (the digests were current on 2026-10-14; images are rebuilt by their
# maintainers, so their creation dates are newer than the pinned GHC 9.10.3, cabal 3.14.1.1 and cuddle themselves).
# Pins: GHC 9.10.3 and cabal-install 3.14.1.1 come with the base image, which is fixed by digest (tag
# haskell:9.10.3-slim-bookworm). Dependency versions are solved against the Hackage index as of INDEX_STATE, so the
# same cuddle source and index give the same plan. cuddle 1.8.1.0 was uploaded 2026-07-15.
FROM haskell@sha256:4f1f62fec9cea4cbb03e885c9ad45642609cadd3897b1e64a7f61d0862b575b1 AS build
ARG CUDDLE_VERSION=1.8.1.0
ARG INDEX_STATE=2026-09-20T00:00:00Z
RUN apt-get update && apt-get install -y --no-install-recommends libgmp-dev zlib1g-dev && rm -rf /var/lib/apt/lists/*
RUN cabal update --index-state="${INDEX_STATE}" \
 && cabal install "cuddle-${CUDDLE_VERSION}" --index-state="${INDEX_STATE}" --jobs=4 \
      --install-method=copy --installdir=/out --overwrite-policy=always \
      --enable-executable-stripping --disable-documentation \
 && /out/cuddle --version

# Runtime: Debian bookworm-slim (the base of the haskell image), only libgmp and Python for the cross-check's worker.
FROM debian@sha256:7c7b2c966bc9ee8cedfeef67e0e279108992c77681fa595db4a9d65c06ccc587
RUN apt-get update && apt-get install -y --no-install-recommends libgmp10 python3 && rm -rf /var/lib/apt/lists/*
COPY --from=build /out/cuddle /usr/local/bin/cuddle
WORKDIR /work
ENTRYPOINT ["/usr/local/bin/cuddle"]
