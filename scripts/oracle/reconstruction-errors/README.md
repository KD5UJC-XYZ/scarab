# Decoder failure versus reconstruction defect

Status: current, bounded primitive control for #493's reconstruction prerequisite.

`Main.hs` checks the installed native `cborg-0.2.10.0` decoder: an accepted word returns
`Right`, an explicit decoder refusal returns `Left`, and an unexpected exception escapes
with its original typed payload. All three observations were true in image
`sha256:01961cacb7812313680b6dd5641ddf7ad77c2159d3492d5a7d3b3887274f092b`.

From this directory, with that existing reference image:

```sh
docker run --rm --cpus 2 --memory 4g --network none \
  -v "$PWD/Main.hs:/tmp/OwnerProbe.hs:ro" --entrypoint bash \
  sha256:01961cacb7812313680b6dd5641ddf7ad77c2159d3492d5a7d3b3887274f092b \
  -c 'runghc -package-db=/root/.local/state/cabal/store/ghc-9.10.3-1be3/package.db -package=cborg /tmp/OwnerProbe.hs'
```

This is a CBOR primitive observation, not an executed consensus snapshot test or a full
serialization conformance gate. Snapshot ownership is separately traced in consensus
`82ecba329d7d054340bf707d44fe6e9ac27cec40`: `Util/CBOR.hs:181–230` maps incremental
`Fail` to `ReadIncrementalErr` without catching arbitrary exceptions;
`Storage/LedgerDB/API.hs:606–635` retries snapshots only for returned `Left` initialization
results. The reference image used here does not expose the consensus module for executing
that complete path.

Scala controls exercise terminal errors through native codec alternatives/optional fields,
binary composition, actual snapshot restore/write owners and the serving storage owner.
The ordinary corruption fallback and persisted fingerprint tests remain separate controls.
