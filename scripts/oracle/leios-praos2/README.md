<!-- SPDX-License-Identifier: Apache-2.0 -->
# Leios Praos2 header oracle

This offline harness compiles the actual `validateHeader`, `validateHeaderProtocol`,
`tickHeaderState`, `reupdateChainDepState` and `minCertificationSlot` functions at
ouroboros-consensus `2416493bfdbe93b761b3a4bb1ccf2b4a913e5e4b`. The dependency pins are the
public testnet's `rc-2026w44` build, recorded in [the reference registry](../../../docs/data/reference-pins.json)
and [cabal.project](cabal.project). It adds `liburing-dev` to the
[Leios decoder image](../leios-testnet/README.md), then builds consensus itself.

The harness uses GHC 9.10.3, whereas the release image uses GHC 9.6. Its Cabal resolution includes
aeson 2.2.5.1; it is an executed source-reference helper, not the release's complete binary or
dependency closure. The copied node constraints and source-package revisions are retained.

The input is the [maintained testnet capture](../../../fixtures/leios/testnet/README.md).
`praos2_cases.py` removes only the ChainSync era/tag-24 wrapper and derives the initial pool
distribution from the captured genesis. The forecast supplies that distribution and the recorded
protocol parameters for the first two epochs. No Dijkstra block ledger is run. The view includes
committee, quorum and transaction-byte bounds, but header validation never consults those fields.

`--batch` applies each case's unmodified recorded prefix, then its supplied forecast/state
overrides and final header. Mutations retain the old signature to expose cheap-check precedence.
`--sign` separately produces valid cold/KES signatures using public deterministic test seeds;
the templates retain the recorded VRF key and proof, and their forecast registers the synthetic
issuer. No operational key material is read or emitted. The native test-only pure KES API is
confined to this fixture generator.

Every accepted protocol-state field is compared: last slot, counters, six nonces and the
announcement (issuer, hash and size). Refusals compare their constructor and domain arguments.
The fixture also retains the complete native diagnostic. The comparison does not establish
equivalence of backend diagnostic strings or the textual rendering of errors; the VRF certificate
is already retained in the input header. This is header validation and state-transition evidence,
not ledger/body validation, forecast production, certificate aggregation, forging or live-node
interoperability. `reupdate` cases exercise the trusted-header path and do not imply that a
mutated unsigned header passes validation.

From the repository root, after building the decoder image as its README describes:

```sh
docker build --build-arg LEIOS_REFERENCE_IMAGE=scarab-leios-testnet:local \
  -f scripts/oracle/leios-praos2/Dockerfile -t scarab-leios-praos2-reference:local .
scarab_praos2_run="$(mktemp -d)"
python3 scripts/oracle/leios-praos2/praos2_cases.py \
  --inputs "$scarab_praos2_run/inputs.json" --sign-inputs "$scarab_praos2_run/sign-inputs.json"
docker run --rm -v "$scarab_praos2_run:/inputs:ro" scarab-leios-praos2-reference:local \
  --sign /inputs/sign-inputs.json > "$scarab_praos2_run/signed.json"
python3 scripts/oracle/leios-praos2/praos2_cases.py --signed "$scarab_praos2_run/signed.json" \
  --cases-inputs "$scarab_praos2_run/cases-inputs.json"
docker run --rm -v "$PWD/fixtures/leios/testnet/config:/config:ro" \
  -v "$scarab_praos2_run:/inputs:ro" scarab-leios-praos2-reference:local \
  /config/shelley-genesis.json /config/dijkstra-genesis.json /inputs/inputs.json \
  > "$scarab_praos2_run/baseline.json"
docker run --rm -v "$PWD/fixtures/leios/testnet/config:/config:ro" \
  -v "$scarab_praos2_run:/inputs:ro" scarab-leios-praos2-reference:local \
  /config/shelley-genesis.json /config/dijkstra-genesis.json /inputs/cases-inputs.json --batch \
  > "$scarab_praos2_run/cases-native.json"
python3 scripts/oracle/leios-praos2/praos2_cases.py --signed "$scarab_praos2_run/signed.json" \
  --reference "$scarab_praos2_run/cases-native.json" --out "$scarab_praos2_run/cases.jsonl"
```

The baseline's `headers` array is the maintained `headers.jsonl`, one compact JSON object per line.
Compare parsed JSON values rather than JSON object key order. The Scala comparison is
`node/testOnly *Praos2ReferenceSuite`, run in the [JDK container](../../../CONTRIBUTING.md#build-docker-only).
After retaining compact results, remove the exact disposable directory created above. Raw build
and run artifacts are not published; the maintained [fixture](../../../fixtures/reference/leios-praos2/README.md)
is the reproducible evidence.
