# Wallet and peer snapshot observers

`Main.hs` reads `fixtures/reference/wallet-queries/inputs.json`, decodes the original Conway states,
and invokes the actual native `getRewardInfoPools` and `getNonMyopicMemberRewards` APIs. It serializes
the results at Conway's low protocol version, 9. It does not reimplement their formulas.

`PeerSnapshots.hs` invokes the released native `encodeLedgerPeerSnapshot` and
`accumulateBigLedgerStake`. Fetch the seven modules listed in the fixture manifest from
ouroboros-network **c45735a56c567fa977969173d18943bac6bb3821** into a private source directory;
compile that directory on the GHC include path. Do not substitute the current source-watch head:
it has removed the legacy encoding still supported by cardano-node 11.1.3.

Build and run both observers in Docker only. The wallet observer reuses the dependency environment of
`scarab-135-reward-oracle:local` (Shelley 1.19.0.1, core 1.21.0.0, Conway 1.23.0.0):

```sh
cabal exec -- ghc -Wall -Werror -L/usr/local/lib -optl-Wl,-rpath,/usr/local/lib \
  -package cardano-ledger-shelley -package cardano-ledger-conway \
  -package cardano-ledger-core -package cardano-ledger-binary -package cardano-slotting \
  -package aeson -package base16-bytestring Main.hs -o wallet-oracle
./wallet-oracle inputs.json
```

The peer observer uses the cached dependencies of
`scarab-v02-49-protocols-cache:peer-acceptance-20261019`; GHC 9.10.3, `-XGHC2021
-XDeepSubsumption`, and an explicit package database. Use `-hide-all-packages` and expose base,
aeson, bytestring, text, deepseq, nothunks, quiet, cardano-binary, cardano-slotting, serialise, cborg,
dns (its public library), iproute, network, io-classes, containers and base16-bytestring. This avoids
ambiguity between the image's multiple installed dependency units. Compile `PeerSnapshots.hs` with
`-i<private-source-directory>` and a private output directory, then capture its eleven hex lines.

The Scala input builder is `WalletQueryCases`; run it from the repository root, for example with
`set node / Test / run / fork := false` followed by
`node/Test/runMain xyz.kd5ujc.scarab.node.ntc.WalletQueryCases /tmp/wallet-query-inputs.json`.
It builds native typed state directly. Ordinary tests use the checked-in inputs and native answers;
they do not regenerate expectations from Scala.

The continuing `check_conformance_sources.py` report watches these ledger and released-network
semantic sources at the pins and paths in the fixture manifest. Its separate query watches preserve
the network-test corpus's own pin. Source changes are review leads; freshness does not demonstrate
behavioral equivalence or update any expected answer automatically.
