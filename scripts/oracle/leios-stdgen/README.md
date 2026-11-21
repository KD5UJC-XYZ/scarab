<!-- SPDX-License-Identifier: Apache-2.0 -->
# Leios fetch generator oracle

The Leios fetch decision draws the job it gives a peer from the reference's `StdGen`
(`pickLeastRequestedJobExcept`, `LeiosDemoTypes/LeiosJobs.hs` of ouroboros-consensus
`2416493bfdbe93b761b3a4bb1ccf2b4a913e5e4b`). Scarab's `consensus.leios.StdGen` mirrors that
generator. These programs print the values that `StdGenSuite` and `LeiosFetchLogicSuite`
compare it with:

- [Vec.hs](Vec.hs) prints the seed and gamma of `mkStdGen`, the first 64-bit words, the draws of
  `uniformR` on an `Int`, the state after one draw and both halves of `splitGen`.
- [Picks.hs](Picks.hs) prints the order in which the jobs of one multiplicity bucket are drawn. It
  is a transcription of the draw loop of `pickLeastRequestedJobExcept` and `pickJobs`, run on the
  reference's `random`, not the reference's functions themselves: `uniformR (0, size - 1)` picks the
  id at that index of the ascending list, and the drawn id is then excluded.
- [Order.hs](Order.hs) prints how `PackedBytes 32` (the reference's `EbHash`) orders four pairs of
  hashes. It is compiled against the `cardano-crypto-class-2.6.0.0` unit of the same store. The pairs
  separate the unsigned lexicographic order of the bytes from the signed order and from the
  word-reversed order.

They link the units that the Leios build links: `random-1.3.1-126384cd` at `random` d83e66f4 and
`splitmix-0.1.3.2-f11367c6` at `splitmix` 419c7638, both registered in
[the reference registry](../../docs/data/reference-pins.json). The source of `random` at that commit
is the source of the Hackage release 1.3.1, and the source of `splitmix` at that commit is the source
of the Hackage release 0.1.3.2. Those units are in the cabal store of the Leios reference image
(`scarab-leios-testnet:local`, [the Leios decoder image](../leios-testnet/README.md)). The image's
GHC 9.10.3 compiles the programs:

```sh
docker run --rm -v "$PWD/scripts/oracle/leios-stdgen":/w:ro --entrypoint sh scarab-leios-testnet:local -c '
  db=/root/.local/state/cabal/store/ghc-9.10.3-1be3/package.db
  cd /tmp && cp /w/*.hs . &&
  ghc -package-db $db -package random -package splitmix Vec.hs -o vec && ./vec &&
  ghc -package-db $db -package random Picks.hs -o picks && ./picks &&
  id=$(ghc-pkg --package-db $db field cardano-crypto-class id | grep 2.6.0.0 | head -1 | cut -d" " -f2) &&
  ghc -package-db $db -package-id $id -package bytestring Order.hs -o order && ./order'
```

The two suites hold the printed values as literals. The output itself is not committed.
