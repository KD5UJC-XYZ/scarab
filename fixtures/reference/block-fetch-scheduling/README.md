# Native BlockFetch scheduling observations

The corpus observes ouroboros-network `2fb69829a19b5c04e4fb212bdd78fb63f9d2d776`.
`decisions.jsonl` contains 256 generated observations (128 original `TestChainFork`
inputs and original `PeerGSVT` generators, each replayed in both Praos fetch modes),
plus 33 explicitly constructed boundary observations. No generated seeds are discarded.
The latter are additional native examples, not additional upstream generator cases.

The observer calls upstream preprocessing, `prioritisePeerChains`,
`filterNotAlreadyInFlightWithOtherPeers` and `fetchRequestDecisions` directly.
It records complete fragment anchors, headers, in-flight state, timing estimates,
salted peer tie breakers, rankings, selected requests and typed refusal payloads.
The consensus callback compares full candidate fragment lengths. This tests the
fetch scheduler with that supplied preorder; it does not implement or establish
Ouroboros Genesis consensus equivalence.

The abstract mock header hashes are represented losslessly as the original signed
64-bit `Int` with its sign bit flipped, encoded big-endian. This preserves native
mock hash ordering when the Scala scheduler compares byte identifiers. Original
slots, body sizes and chain relationships are retained. These are scheduler inputs,
not fabricated Cardano wire messages.

Boundary cases cover global admission in both modes, preferred-peer handover
above the nominal concurrency target, whole-block byte-watermark overshoot,
request windows and discontiguous ranges, low/high equality boundaries, states
that cannot serve blocks, overlaps, other-peer claims and GSV ties.
`decisions.jsonl.gsv.jsonl` separately records eight native default/sample/EWMA
observations including one-picosecond and time-constant boundaries.

Regenerate with the original source checkout and a Haskell development image:

```sh
python3 scripts/oracle/block-fetch-scheduling/export.py \
  --source /path/to/ouroboros-network \
  --probe-dir /tmp/scarab-fetch-observer-new \
  --output-dir fixtures/reference/block-fetch-scheduling \
  --allow-download
```

By default the exporter uses the local pinned-protocol development cache image.
`--allow-download` permits downloading missing dependencies at the pinned project
index states; without it the container is offline. `--image` and `--cache-build`
can reuse a compatible prepared cache. Production upstream source and generators
are unchanged; the observer is a separate executable. The manifest records source,
observer, exporter, payload, executable, dependency-plan and image hashes.

Run `node/testOnly xyz.kd5ujc.scarab.node.UpstreamBlockFetchPolicySuite` in the
repository's JDK container. Full `check` retains this regression alongside the
separate production protocol/runtime replay suites. These finite observations do
not assert identical wall-clock schedules or performance.

Upstream code licensing is retained in the [network license](../../licenses/block-fetch-network-LICENSE) and [notice](../../licenses/block-fetch-network-NOTICE).
