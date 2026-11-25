#!/bin/sh
# SPDX-License-Identifier: Apache-2.0
# Builds the pinned libsodium as fixtures/witness/archive/pinned-source-oracle records it (configure
# flags, CFLAGS without ED25519_COMPAT), compiles verify.c against it and runs it on /io/cases.tsv.
# Expects, read-only: /in/libsodium-dbb48cce.tar.gz (codeload.github.com/IntersectMBO/libsodium/tar.gz/
# dbb48cce5429cb6585c9034f002568964f1ce567, SHA-256 e4f29ae3c16037e484bb69e3fa22a5565c42adf497f8f88e61ff8d9486ab863e),
# /oracle/verify.c and /io/cases.tsv; writes /out/sodium-results.tsv. Run with no network.
set -eu
src=libsodium-dbb48cce5429cb6585c9034f002568964f1ce567
echo "e4f29ae3c16037e484bb69e3fa22a5565c42adf497f8f88e61ff8d9486ab863e  /in/libsodium-dbb48cce.tar.gz" | sha256sum -c -
mkdir -p /tmp/b && cd /tmp/b
tar xzf /in/libsodium-dbb48cce.tar.gz
mkdir build && cd build
CC=gcc CFLAGS="-O2 -g0 -UED25519_COMPAT" "../$src/configure" --prefix=/tmp/b/install --enable-shared \
  --disable-static --disable-dependency-tracking > /tmp/b/configure.log 2>&1
make -j3 V=1 > /tmp/b/make.log 2>&1
grep 'ref10/open.c' /tmp/b/make.log | grep -o -- '-O2 -g0 -UED25519_COMPAT' >&2
sha256sum src/libsodium/.libs/libsodium.so.23.3.0 >&2
gcc --version | head -1 >&2
gcc -O1 -I"../$src/src/libsodium/include" -I"../$src/src/libsodium/include/sodium" -Isrc/libsodium/include \
  -o /tmp/b/verify /oracle/verify.c -Lsrc/libsodium/.libs -lsodium
LD_LIBRARY_PATH=src/libsodium/.libs /tmp/b/verify < /io/cases.tsv > /out/sodium-results.tsv
