# Native Genesis BlockFetch observations

The pin and every source, executable, observer, build-plan and payload digest are in
[manifest.json](manifest.json). The observer calls the unchanged native
`fetchDecisionsGenesisM` from network `2fb69829a19b5c04e4fb212bdd78fb63f9d2d776`.

The 128 original `TestChainFork` and `PeerGSVT` samples retain seeds 330001–330128 and
sizes 20–39. Seven additional cases cover starvation rotation, supplied validation,
already-fetched candidates, a busy chosen peer, zero request credit, disconnect alignment
and own in-flight blocks. Every emitted decision, refusal payload, order/current/start
update and dynamo demotion is compared; no outcomes are excluded. Candidate inputs are
native preprocessing results. Mock signed hashes use the same lossless sign-bit mapping
as the existing Praos corpus. Native Genesis concurrency refusals remain distinct from
Praos bulk-mode concurrency refusals.

Generate in Docker, using the pinned git checkout and optional shared build cache:

```sh
python3 scripts/oracle/genesis-block-fetch/export.py \
  --source /path/to/ouroboros-network \
  --probe-dir /tmp/scarab-genesis-fetch \
  --cache-build /path/to/prepared/builddir \
  --output-dir fixtures/reference/genesis-block-fetch
```

The default cache image is `scarab-148-fetch-reference-cache:local`; `--image` can select
another image with the same pinned dependencies. Offline is the default; `--allow-download`
explicitly enables dependency download. Upstream license and notice are retained at
[network LICENSE](../../licenses/block-fetch-network-LICENSE) and
[network NOTICE](../../licenses/block-fetch-network-NOTICE).
