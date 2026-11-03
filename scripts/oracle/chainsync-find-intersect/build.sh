# SPDX-License-Identifier: Apache-2.0
# Compiles the find-intersect observer against the released packages of the image's store (see README.md).
set -e
DB=$(ls -d /root/.local/state/cabal/store/ghc-*/package.db)
ghc -package-env - -hide-all-packages -package-db $DB -package base -package bytestring -package cborg -package serialise \
  -package-id aeson-2.2.5.0-339d6fee3de193e2bbf41260d8e8e7b362b1ccb7269b7adf75b19a355597fd8a \
  -package-id base16-bytestring-1.0.2.0-4ebd66b96c60d9abced623339db3dbb78bb1a56e50c694b247779aa8f0c86769 \
  -package text \
  -package-id cardano-slotting-0.2.2.0-983d14fcfcaf70fabf1b82c18eb427299307f72f63438e48f79ad5b676e41829 \
  -package-id typed-protocols-1.2.1.0-8c60d8132922c119353bb07538d5c224377175972c7feec3e38631ba7a87d8d5 \
  -package-id ouroboros-network-1.2.0.0-l-api-75505d9cab9a701aa9ede07640fa53619dfce10ddedeb2a821868f540f9775ce \
  -package-id ouroboros-network-1.2.0.0-l-protocols-1a3cef254fbf3f0ef603ee6eae9beba74ab2dadd5865f5321a88dd15f154bbcd \
  -Wall -Werror -odir /observer/o -hidir /observer/o /observer/Main.hs -o /observer/find-intersect-observer
