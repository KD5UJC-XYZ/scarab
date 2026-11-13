# SPDX-License-Identifier: Apache-2.0
# The checksum-pinned cardano-node 11.1.3 reference image (docs/reference-image.md) with the trial's configurator and
# Haskell pool start scripts. The configurator and the two Haskell pools run this image.
FROM cardano-reference-11.1.3:local
ENV CARDANO_CLI=/opt/reference/bin/cardano-cli \
    CARDANO_NODE=/opt/reference/bin/cardano-node
COPY --chmod=0555 configure.sh haskell-node.sh /opt/at/
