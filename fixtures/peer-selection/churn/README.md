# Native Cardano churn policy

These 1,152 explicit boundary cases execute the unchanged pinned
`Cardano.Network.PeerSelection.Churn.peerChurnGovernor` in native `IOSim` virtual time.
The network pin is `2fb69829a19b5c04e4fb212bdd78fb63f9d2d776`; the six compiled Cardano
source hashes, package identities, image and executable are recorded in
[manifest.json](manifest.json). The maintained [observer](../../../scripts/oracle/churn/Main.hs)
and [exporter](../../../scripts/oracle/churn/export.py) compile these original modules directly
against an existing read-only network build. They do not rewrite the module or copy its policy.

Inputs cover the native Praos bulk/deadline modes and Genesis fetch mode; both ledger judgements;
bootstrap disabled or enabled with an empty list; four local hot valencies; four native random
seeds; three distinct lawful deadline/sync target pairs; and static inputs or changes to fetch,
bootstrap and ledger judgement during the three-second startup interval. Genesis fetch mode
remains Genesis fetch mode across that transition. Scarab's bulk/deadline observations both map
to that same native Genesis policy; the Scala comparison checks both.

Each observation retains initial and post-startup targets, the first two actual native wait
values, and all twelve first-cycle actions with raw proposals, native sanity decisions, signed
achieved changes, completion durations and timeout outcomes. The controlled counter action
immediately follows sane target updates and retains its previous values for insane updates,
using the native `sanePeerSelectionTargets` predicate, as the native monitor requires. This
isolates churn decisions; it does not simulate real peer acquisition delays or claim complete
peer-governor schedule equality. Every selected first-cycle result is retained, including
negative active targets and root targets exceeding known targets.

The observer also records the exact binary rational of the first native `randomR` Double draw.
Tests compare the production delay helper to the exact first native wait in picoseconds.
Native `realToFrac Double :: DiffTime` floors at picosecond resolution. Native and Scala random
seed streams are not assumed equivalent. The second wait is retained as native evidence, while
the pure comparison of random delay uses the first draw whose explicit rational is supplied.
These cases are new explicit boundary inputs, not exported upstream-generated schedules.

Regenerate offline with the existing pinned build and matching image:

```sh
python3 scripts/oracle/churn/export.py \
  --reference-build /tmp/scarab/148-fetch-oracle-final-2 \
  --probe-dir /tmp/scarab/native-churn-regeneration \
  --output-dir fixtures/peer-selection/churn
```

The probe is owned by the invoking task; preserve compact hashes and results, then remove its
compiler objects and executable. The original source and shared caches remain read-only.
