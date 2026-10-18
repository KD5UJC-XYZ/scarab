# SPDX-License-Identifier: Apache-2.0
# First build the pinned scripts/oracle/plutus/Dockerfile base (see README).
FROM scarab-plutus175-oracle:local
RUN apt-get update \
 && apt-get install -y --no-install-recommends autoconf automake libtool \
 && rm -rf /var/lib/apt/lists/*
# cardano-node 11.1.3 flake.lock: sodium and sodium_2 use this exact revision.
ARG SODIUM_COMMIT=dbb48cce5429cb6585c9034f002568964f1ce567
RUN git init -q /native-sodium \
 && cd /native-sodium \
 && git fetch -q --depth 1 https://github.com/input-output-hk/libsodium.git ${SODIUM_COMMIT} \
 && git checkout -q FETCH_HEAD \
 && test "$(git rev-parse HEAD)" = "${SODIUM_COMMIT}" \
 && cp /usr/share/misc/config.guess build-aux/config.guess \
 && cp /usr/share/misc/config.sub build-aux/config.sub \
 && DO_NOT_UPDATE_CONFIG_SCRIPTS=1 ./autogen.sh \
 && ./configure --prefix=/usr/local \
 && make -j4 \
 && make install \
 && ldconfig
WORKDIR /oracle/project
COPY scripts/oracle/byron/full/cabal.project scripts/oracle/byron/full/cabal.project.freeze ./
COPY scripts/oracle/byron/full/Main.hs scripts/oracle/byron/full/scarab-byron-registration.cabal harness/
RUN cabal build scarab-byron-registration --jobs=4 --extra-lib-dirs=/usr/local/lib \
 && cp "$(cabal list-bin scarab-byron-registration)" /usr/local/bin/scarab-byron-registration
ENTRYPOINT ["/usr/local/bin/scarab-byron-registration"]
