# SPDX-License-Identifier: Apache-2.0
# The portable libblst JNI library that Scarab ships (ADR 0012, roadmap B25): blst v0.3.15, the version of the
# reference node (iohk-nix builds it with ./build.sh -D__BLST_PORTABLE__). The same toolchain and
# recipe as foundation.icon:blst-java's release workflow (ubuntu-22.04, bindings/java/build.sh of
# supranational/blst), but with -D__BLST_PORTABLE__ so that the library runs on x86-64 CPUs without
# ADX/BMI2. See the ADR for the blst version and why.
#
#   docker build -f docker/blst.Dockerfile --target artifact -o blst-native/out .
#
# then copy blst-native/out/libblst.so to
# blst-native/src/main/resources/supranational/blst/Linux/amd64/libblst.so and put its sha256
# (blst-native/out/libblst.so.sha256) in Blst.portableSha256. blst-native/out is untracked.
#
# The Ubuntu image and signed package snapshot freeze compiler, JNI headers and transitive tools.
# APT still verifies Ubuntu signatures and package hashes. Only snapshot expiry is disabled:
# a historical signed index must remain usable after its original freshness window.
FROM ubuntu@sha256:5ec03bb3441e8b0bf3b4f9cd4629a1ae763010dc3035bb8da3ae6cf026486401 AS build
# The minimal base has no CA store. This bootstrap package is pinned by the signed snapshot's
# Packages index; BuildKit verifies its hash before extraction and still verifies HTTPS itself.
ADD --checksum=sha256:6e8cdcc8c86103acd4fc14649eac62ff2037108389074a7b167567af33c32245 \
    https://snapshot.ubuntu.com/ubuntu/20261020T000000Z/pool/main/c/ca-certificates/ca-certificates_20260601~22.04.1_all.deb \
    /tmp/ca-certificates.deb
RUN dpkg-deb -x /tmp/ca-certificates.deb /tmp/ca \
 && mkdir -p /etc/ssl/certs \
 && cat /tmp/ca/usr/share/ca-certificates/mozilla/*.crt > /etc/ssl/certs/ca-certificates.crt \
 && rm -rf /tmp/ca /tmp/ca-certificates.deb /etc/apt/apt.conf.d/docker-clean
RUN rm -f /etc/apt/sources.list.d/* \
 && printf '%s\n' \
      'deb [check-valid-until=no] https://snapshot.ubuntu.com/ubuntu/20261020T000000Z jammy main universe' \
      'deb [check-valid-until=no] https://snapshot.ubuntu.com/ubuntu/20261020T000000Z jammy-updates main universe' \
      'deb [check-valid-until=no] https://snapshot.ubuntu.com/ubuntu/20261020T000000Z jammy-security main universe' \
      > /etc/apt/sources.list \
 && apt-get -o Acquire::AllowInsecureRepositories=false update \
 && apt-get -o APT::Get::AllowUnauthenticated=false install -y --no-install-recommends \
      swig=4.0.2-1ubuntu1 g++=4:11.2.0-1ubuntu1 make=4.3-4.1build1 \
      git=1:2.34.1-1ubuntu1.18 ca-certificates=20260601~22.04.1 \
      binutils=2.38-4ubuntu2.12 openjdk-17-jdk-headless=17.0.20.1+1-1~22.04 \
 && mkdir -p /out/apt \
 && cp /var/lib/apt/lists/*InRelease /out/apt/ \
 && cp /usr/share/keyrings/ubuntu-archive-keyring.gpg /out/apt/ubuntu-archive-keyring.gpg \
 && for index in /out/apt/*InRelease; do \
      gpgv --status-fd 1 --keyring /out/apt/ubuntu-archive-keyring.gpg "$index" > "$index.signature.txt" || exit 1; \
    done \
 && sha256sum /var/cache/apt/archives/*.deb > /out/downloaded-debs.sha256 \
 && dpkg-query -W -f='${binary:Package}\t${Version}\t${Architecture}\n' | LC_ALL=C sort > /out/packages.tsv \
 && rm -rf /var/lib/apt/lists/*
ARG BLST_COMMIT=6d960cd05d6fe2b5bc9ba161edf0c1a131b87c4c
# Pin the existing ADX + runtime-dispatch build even when the builder CPU lacks ADX.
ARG BLST_FLAGS=-D__ADX__ -D__BLST_PORTABLE__
ARG BLST_SOURCE_ARCHIVE_SHA256=15e976ff729ebac12da5377ba454b6761aa7f77747cf5693074ee99336637024
WORKDIR /src
RUN git init -q blst \
 && cd blst \
 && git fetch -q --depth 1 https://github.com/supranational/blst.git ${BLST_COMMIT} \
 && git checkout -q FETCH_HEAD \
 && test "$(git rev-parse HEAD)" = "${BLST_COMMIT}" \
 && git archive --format=tar HEAD > /out/blst-source.tar \
 && printf '%s  %s\n' "${BLST_SOURCE_ARCHIVE_SHA256}" /out/blst-source.tar | sha256sum -c - \
 && git rev-parse HEAD > /out/blst-commit.txt \
 && git rev-parse 'HEAD^{tree}' > /out/blst-tree.txt
WORKDIR /src/blst/bindings/java
ENV JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
COPY blst-native/native/raw-dst.c /src/raw-dst.c
# The Java build.sh hands its arguments to the top-level build.sh, which compiles libblst.a with
# them; it then links the SWIG JNI glue into libblst.so, as blst-java's workflow does.
RUN CXX=g++ ./build.sh ${BLST_FLAGS} \
 && gcc -c -fPIC -fvisibility=hidden -O -Wall -Werror \
      -I"${JAVA_HOME}/include" -I"${JAVA_HOME}/include/linux" -I.. \
      /src/raw-dst.c -o raw-dst.o \
 && g++ -shared -fPIC -fvisibility=hidden -O -Wall -Wno-unused-function \
      -I"${JAVA_HOME}/include" -I"${JAVA_HOME}/include/linux" -I.. \
      blst_wrap.cpp raw-dst.o libblst.a -Wl,-Bsymbolic \
      -o supranational/blst/Linux/amd64/libblst.so \
 && g++ --version | head -1 && swig -version | sed -n 2p \
 && dpkg-query -W -f='${Package} ${Version}\n' openjdk-17-jdk-headless binutils \
 && sha256sum /src/raw-dst.c > /out/raw-dst.sha256 \
 && cp supranational/blst/Linux/*/libblst.so /out/libblst.so \
 && cd /out && sha256sum libblst.so > libblst.so.sha256 \
 && nm -D --defined-only libblst.so | awk '{print $3}' | LC_ALL=C sort > exports.txt \
 && nm libblst.so | awk '{print $3}' | LC_ALL=C sort > symbols.txt \
 && readelf -hSWdn libblst.so > elf.txt \
 && objdump -d libblst.so > disassembly.txt \
 && cat libblst.so.sha256

FROM scratch AS artifact
COPY --from=build /out/ /
