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
# (blst-native/out/libblst.so.sha256) in BlstNative.portableSha256. blst-native/out is untracked.
#
# The base image is the one blst-java's own workflow builds on (ubuntu-22.04: gcc 11.4, SWIG 4.0.2,
# glibc 2.35, so the library needs no newer glibc than the upstream one), pinned by digest. The
# packages come from the distribution's archive; the versions used are printed into the build log
# and recorded in ADR 0012.
FROM ubuntu@sha256:5ec03bb3441e8b0bf3b4f9cd4629a1ae763010dc3035bb8da3ae6cf026486401 AS build
RUN apt-get update \
 && apt-get install -y --no-install-recommends swig g++ make git ca-certificates binutils openjdk-17-jdk-headless \
 && rm -rf /var/lib/apt/lists/*
ARG BLST_COMMIT=6d960cd05d6fe2b5bc9ba161edf0c1a131b87c4c
ARG BLST_FLAGS=-D__BLST_PORTABLE__
WORKDIR /src
RUN git init -q blst \
 && cd blst \
 && git fetch -q --depth 1 https://github.com/supranational/blst.git ${BLST_COMMIT} \
 && git checkout -q FETCH_HEAD \
 && test "$(git rev-parse HEAD)" = "${BLST_COMMIT}"
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
 && mkdir /out \
 && cp supranational/blst/Linux/*/libblst.so /out/libblst.so \
 && cd /out && sha256sum libblst.so > libblst.so.sha256 \
 && nm -D --defined-only libblst.so | awk '{print $3}' | LC_ALL=C sort > exports.txt \
 && nm libblst.so | awk '{print $3}' | LC_ALL=C sort > symbols.txt \
 && cat libblst.so.sha256

FROM scratch AS artifact
COPY --from=build /out/ /
