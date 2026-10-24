# SPDX-License-Identifier: Apache-2.0
# Built on the Byron registration oracle image (scripts/oracle/byron/full, see its README), whose
# frozen plan already holds the released cardano-crypto-wrapper 1.7.0.0, cardano-crypto 1.3.0 and
# crypton 1.0.6 that cardano-node 11.1.3 links; this harness adds no dependency of its own.
FROM scarab-byron171-full-reference:local
WORKDIR /oracle/donna
RUN sed 's/^packages: harness$/packages: donna/' /oracle/project/cabal.project > cabal.project \
 && cp /oracle/project/cabal.project.freeze .
COPY scripts/oracle/byron-donna/Main.hs scripts/oracle/byron-donna/scarab-byron-donna.cabal donna/
RUN cabal build scarab-byron-donna --offline --jobs=4 \
 && cp "$(cabal list-bin scarab-byron-donna)" /usr/local/bin/scarab-byron-donna
ENTRYPOINT ["/usr/local/bin/scarab-byron-donna"]
