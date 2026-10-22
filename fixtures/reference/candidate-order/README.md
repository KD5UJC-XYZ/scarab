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

`hfc.jsonl` adds 55 separate native hard-fork comparisons: all 42 ordered pairs of
supported eras, seven same-era controls, and six Babbage/Conway distance, length and
certificate controls. The observer uses actual Cardano `HardForkTiebreakerView`,
its `Ord` instance, and `preferCandidate` with the actual per-era configuration
wrapper. Pre-Conway configurations are unrestricted; Conway uses a five-slot
restriction. Every result includes the forward/reverse preference decisions,
raw ordering and stable sorted identifiers. This directly checks the absence of
a Byron/Shelley tiebreaker and the later era's eligibility configuration across
Shelley-era boundaries. No Dijkstra inputs or outputs are exported; the pinned
Cardano type's complete configuration necessarily includes its future-era entry.
These are constructed view comparisons, not claims of header signature validity
or additional upstream-generated scenarios.

The complete HFC observer needs two dependencies absent from the base reference
cache image. Pass `--allow-download` to permit Cabal to fetch their exact versions
from the pinned build plan. The source archive remains unchanged.
