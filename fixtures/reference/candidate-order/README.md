# Native candidate ordering

`native.jsonl` retains all 24 constructed input rows in
`scripts/oracle/candidate-order/inputs.jsonl` and every output from actual consensus
`82ecba329d7d054340bf707d44fe6e9ac27cec40`. These are finite differential examples,
not exported upstream generators.

The observer constructs actual `SelectView` values and calls their `Ord` instances,
`preferCandidate`, and `Data.List.sortBy (flip compare)` directly. It does not
translate a comparison algorithm. Praos eligibility uses the configured restricted
VRF rule; global ordering uses the native unrestricted `Ord` rule, as required by
`ChainSel.hs`. PBFT uses the native EBB tiebreaker. `pairwiseOrd` rows and columns
follow the original input order; values are native `LT`, `EQ`, or `GT`. Stable ties,
all permutations of the restricted-VRF example, unequal block numbers, lawful
same-slot/issuer certificate differences, and EBB boundaries are retained.

`sort.jsonl` separately observes seven explicit synthetic, nontransitive comparator
inputs through native `Data.List.sortBy`. These cases test the list-sort mechanism;
they do not describe Praos's global `Ord` or claim lawful signed-header inputs.

The manifest records source, observer, input, output, executable, image and build-plan
hashes, together with exact successful reproduction commands. The original pinned
source archive is unchanged; the observer is an added executable. Reproduce on a
compute host with Docker:

```sh
python3 scripts/oracle/candidate-order/export.py \
  --source /path/to/pinned/ouroboros-consensus \
  --probe-dir /tmp/scarab/candidate-order-native \
  --output-dir /tmp/scarab/candidate-order-output
```

Generated build data and logs remain outside the checkout. The maintained Scala
comparison tests consume every row and every listed ordering/eligibility field.
