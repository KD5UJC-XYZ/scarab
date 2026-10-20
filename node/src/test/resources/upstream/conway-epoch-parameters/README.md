# EPOCH parameter-consistency controls

Status: bounded native/formal comparison and native-state replay for
[#267](https://github.com/KD5UJC-XYZ/scarab/issues/267). Haskell remains the reference.

## Cause of the original disagreement

The unchanged original EPOCH seed `497101` remains under
[conway-epochs](../conway-epochs/README.md), with its failed upstream comparison.
Its completed ratification state enacts no actions and expires no actions, but
its `ensCurPParams` differ from the working governance state's current parameters.
The first differing translated parameter is `ppMonetaryExpansion`: the current
value is `8606909/10000000`, while the completed pulser contains
`633925279839594401/1250000000000000000`. The future-parameter prediction is
`PotentialPParamsUpdate Nothing`.

At native ledger pin `f649f9751074d2ab3de033fc3912f29c9862c1f5`:

- `libs/cardano-ledger-test/src/Test/Cardano/Ledger/Constrained/Conway/Epoch.hs:52–103`
  leaves both future parameters and the completed enactment parameters unrelated
  to the current parameters. Its action-membership constraints do not establish
  their consistency.
- `eras/conway/impl/src/Cardano/Ledger/Conway/Governance.hs:325–335,505–510`
  initializes the pulser's enactment state from the working parameters.
  `predictFuturePParams` at lines 299–323 predicts a change only for an enacted
  parameter update or hard fork. Empty enacted actions cannot explain a changed
  parameter vector.
- `eras/conway/impl/src/Cardano/Ledger/Conway/Rules/Epoch.hs:316–325` adopts
  `nextEpochPParams`; `libs/cardano-ledger-core/src/Cardano/Ledger/State/Governance.hs:106–124`
  returns current parameters without a definite update.
- `libs/cardano-ledger-conformance/src/Test/Cardano/Ledger/Conformance/SpecTranslate/Conway/Cert.hs:133–147`
  passes both the working enactment state and completed ratification state to
  the formal model. `SpecTranslate/Conway/Base.hs:399–409` maps each parameter
  vector independently. There is no swapped field to correct in that adapter.

The formal specification pin `b3a4fd47a1144ff1fb994f89b1045e78159e07ae` is generated
from source `caffe9223abb7b7406da2f3f14a3bc3b5cd2f13f`. In its Conway EPOCH rule,
`es = EnactStateOf fut` and `es' = record es { withdrawals = ∅ }`: it adopts the
completed pulser's parameters directly. See the pinned
[`Ledger.Conway.Specification.Epoch` source view](https://github.com/IntersectMBO/formal-ledger-specifications/blob/b3a4fd47a1144ff1fb994f89b1045e78159e07ae/site/Ledger.Conway.Specification.Epoch.html).
The two rules therefore disagree on this inconsistent generated state. This
classifies the retained case as a missing generator consistency invariant,
not a demonstrated disagreement on a reachable state or a Scarab rule defect.

## Executed controls

The test-only observer patch is
[epoch-parameter-controls.patch](../../../../../../scripts/oracle/ledger-govcert/epoch-parameter-controls.patch).
It adds separate properties and a test-only `data-default` dependency. It does
not change a ledger rule, translation, original property or original fixture.
Apply it after the existing exporter, epoch-family and sized-probe patches.

- `minimal-inconsistent` constructs an otherwise empty default EpochState with
  no enacted/expired actions, no future update and only the completed enactment
  state's monetary expansion changed from zero to one half. The native/formal
  translated-state comparison fails; both evaluators accept. Scarab follows native.
- `minimal-consistent` is the same construction with that single inconsistency
  absent. The upstream translated-state comparison passes.
- Eight consecutive seeds `497201–497208` use a supplemental lawful no-action
  generator. It runs the existing upstream constrained `PParams` generator, fixes the
  Conway protocol version to PV9 at construction and constructs the completed pulser through native `mkEnactState gov`, linking its
  parameters at creation. It does not filter or repair original upstream inputs.
  Every upstream translated-state comparison passes.

The Scala suite compares complete typed native EpochState outputs for all ten
controls, including the negative control. Manifest outcomes and hashes keep
native acceptance separate from native/formal equality. These are ten bounded
controls, not family-wide coverage or a repair of every upstream action-family
constraint. The original case remains a failure rather than being relabelled.

All invocations use `--qc-max-success=1 --qc-max-size=30` with the manifest seed;
the first QuickCheck sample is size zero. Raw logs and private build directories
stay outside the repository. Upstream Apache-2.0 notices are retained under
`fixtures/witness/licenses/`.
