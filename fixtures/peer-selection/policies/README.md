# Native peer-selection policies

These 912 deterministic observations execute the unchanged peer-selection functions in
`ouroboros-network` `2fb69829a19b5c04e4fb212bdd78fb63f9d2d776`:
`Ouroboros.Network.Diffusion.Policies.simplePeerSelectionPolicy` and
`mkHotDemotionPolicy`. The source is Apache-2.0; source hashes and build identity are in
[manifest.json](manifest.json). The maintained observer is
[Main.hs](../../../scripts/oracle/peer-selection/Main.hs).

These are explicit boundary inputs, not original upstream generated test cases. Sixteen native
`StdGen` seeds cover empty/singleton/larger populations, absent/all/mixed tepid flags and zero,
partial, complete and excessive pick counts. Failure counts range from zero to four. Hot scores
include missing, explicit zero and nonzero values, with missing/present joined-slot tie breaks.
All five policy selections and every eligible peer's optional hot score are retained.

The observer calls exported native `addRand` with an initial generator to record the exact
unsigned Word32 draws. Each actual policy call then starts with that same initial generator.
The native `splitGen` stream is not equated with Scarab's PRNG seed stream. Differential tests
pass the explicit words through the ranking helpers used by Scarab's production policy and
compare the selected sets and complete optional-score maps. Selected-set equality does not
claim identical job execution order or random streams.

Regenerate offline using an existing pinned network build and its matching image:

```sh
python3 scripts/oracle/peer-selection/export.py \
  --reference-build /tmp/scarab/148-fetch-oracle-final-2 \
  --probe-dir /tmp/scarab/149-native-policy-regeneration \
  --output-dir fixtures/peer-selection/policies
```

The reference build is mounted read-only; no source or dependency-cache copies are made. The
output/probe paths are owned by the invoking task. Preserve compact hashes/results, then remove
its disposable compiler output after reviewing the regenerated observations. No selected cases,
expected results or score fields are masked or omitted.
