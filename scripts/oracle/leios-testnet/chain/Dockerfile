# SPDX-License-Identifier: Apache-2.0
# The parent image is the Leios testnet oracle (scripts/oracle/leios-testnet/Dockerfile), whose
# project already holds the ledger and cardano-base revisions of cardano-node b004fe0c; this harness
# is a second package of that project, built against the same dependencies.
FROM scarab-leios-testnet:local
WORKDIR /oracle/leios-testnet
COPY scripts/oracle/leios-testnet/chain/scarab-leios-testnet-chain.cabal chain/
COPY scripts/oracle/leios-testnet/chain/Main.hs chain/
RUN printf 'packages: chain\n' > cabal.project.local \
 && cabal build scarab-leios-testnet-chain --jobs=8 --extra-lib-dirs=/usr/local/lib \
 && cp "$(cabal list-bin scarab-leios-testnet-chain)" /usr/local/bin/scarab-leios-testnet-chain
ENTRYPOINT ["/usr/local/bin/scarab-leios-testnet-chain"]
