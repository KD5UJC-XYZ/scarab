# Pinned Conway epoch-rule probes

These original native inputs come from `cardano-ledger`
`f649f9751074d2ab3de033fc3912f29c9862c1f5`, with its pinned formal specification and
constrained generators. The existing export-only CBOR observer is combined with
`scripts/oracle/ledger-govcert/epoch-families.patch`. The latter enables EPOCH and corrects the
NEWEPOCH label to use the genuine `constrainedNewEpoch` bundle. At the pin, that disabled label
actually invokes EPOCH. The patch also retains UTXO's original execution context instead of
generating an unrelated replacement. None of these observer changes modifies a ledger rule.

| Actual rule / seed | Native result | Agda result | Upstream comparison | Scarab |
|---|---|---|---|---|
| EPOCH / 497101 | Accepted | Accepted | Fails: distinct post-states | Full native post-state matches |
| NEWEPOCH / 498101 | Accepted | Accepted | Passes | Unsupported: zero total active stake inside `NonZero Coin` |

The manifest retains runner arguments, source/dependency pins and every original file hash.
`--qc-max-success=1 --qc-max-size=30` begins at generator size zero; it does not select size 30.
The NEWEPOCH bytes are not repaired and the decoder's nonzero invariant is retained. A passing
test that checks this explicit unsupported classification is not a successful Scarab rule replay.
The EPOCH fixture's two accepted verdicts likewise do not mean that the upstream comparison passes.
[#267's parameter-consistency controls](../conway-epoch-parameters/README.md) classify it:
the generator constructs changed completed-pulser parameters without any enacted action, while
its future-parameter prediction retains the current parameters. The native rule and formal rule
choose their documented different parameter sources on that inconsistent input. A minimal
single-parameter negative and a separate lawful no-action generator retain both outcomes;
the original fixture and its failed comparison remain unchanged.

Both remain bounded probes. EPOCH's constrained generator excludes incomplete DRep pulsers and
limits enacted actions; NEWEPOCH's state specification is `trueSpec` and does not establish native
state invariants. Larger explicitly sized probes fail in the upstream proposal constructor before
producing a judgment. Lawful integration traces and broader coverage remain tracked by
[K2 / #49](https://github.com/KD5UJC-XYZ/scarab/issues/49).

Upstream sources are Apache-2.0; notices are retained under `fixtures/witness/licenses/`.
