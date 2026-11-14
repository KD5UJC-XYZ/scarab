<!-- SPDX-License-Identifier: Apache-2.0 -->
# Era-history oracle

**Status: Current** — optional reference oracle for [#118](https://github.com/KD5UJC-XYZ/scarab/issues/118).

`Main.hs` is built as one more executable of the unchanged ouroboros-consensus at
`82ecba329d7d054340bf707d44fe6e9ac27cec40`, against its `ouroboros-consensus` and `cardano` libraries. It
answers `GetInterpreter` as the node does: `mkInterpreter` of `reconstructSummary` over the eras a
hard-fork state has passed and its `TransitionInfo`, encoded with `Serialise`
(`HardFork/Combinator/Ledger/Query.hs:529, 562`; `State/Infra.hs:194-262`). The era parameters are the
production `byronEraParams` and `shelleyEraParams` applied to the repository's preprod genesis files, so
nothing in the answer is Scarab's own reading.

```sh
python3 scripts/oracle/era-history/export.py --source <ouroboros-consensus checkout holding the pin> \
  --probe-dir <new empty scratch directory> --allow-download --keep
```

The build runs in `scarab-v02-49-protocols-cache:peer-acceptance-20261019`, with that image's
`cabal.project` (its `index-state` fixes the package versions; `provenance.json` records the plan's hash).
That image's store does not hold every package the `cardano` library needs, so the first build fetches them
(`--allow-download`). `--keep` keeps the build container, and a later run with the same container name
reuses its store and builds offline. Remove the container (`scarab-118-era-history`) and the probe directory
when done. The output is the [fixture](../../../fixtures/reference/era-history/README.md).
