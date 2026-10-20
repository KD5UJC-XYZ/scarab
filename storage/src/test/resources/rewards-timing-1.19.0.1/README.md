# Native reward availability and accounting

Deterministic observations of the unchanged released Haskell RUPD transition and
`applyRUpd`, generated 2026-10-20 for [#49](https://github.com/KD5UJC-XYZ/scarab/issues/49).
The source is `cardano-ledger-shelley 1.19.0.1`, revision
`4c81e909df7555b6a58a000db8f8f4f89c7acdc4`, core 1.21.0.0 and binary 1.9.1.0,
in the existing frozen node-11.1.3 context image. This released reference pin is
separate from the newer Conway formal-conformance source pin. No formal verdict
is claimed by this API/STS oracle.

`RewardTimingCases` takes the eleven independently serializable epoch-state inputs
of the [existing reward oracle](../rewards-1.19.0.1/README.md), without altering their
stake, registrations, blocks, pools, fees or parameters. These are explicitly
constructed deterministic cases, not random generators; no random seed applies.
Each input runs five schedules within epoch 5 (10,000 slots, k=5, f=1/20):

- dense: exact start boundary, twelve consecutive ticks, exact force boundary,
  first tick after force, last slot;
- force-inclusive: start boundary, first start opportunity, exact force boundary,
  first tick after force, last slot;
- late-first: start boundary, first opportunity only after force, last slot;
- sparse: start, one later pulse, first tick after force, last slot;
- first-at-force: first opportunity exactly at force, next slot, last slot.

The randomness stabilization window is explicitly 400 slots (4k/f). The original
API-only oracle used an irrelevant 100-slot reader window; this STS observation
supplies 400 and executes the real native rule, rather than duplicating its timing
logic in the exporter. `startStep`, `completeStep`, RUPD and `applyRUpd` are imported
unchanged from public package exports. The wrapper contains no reward formulas.

The 55 cases retain 341 native slot observations, including 174 completed-update
and credited EpochState observations. The Scala adapter checks absence before the
start and presence afterwards, mandatory completion strictly past force, every
completed monetary field and the complete encoded credited EpochState. Different
pulse partitions or early completion within the normal interval are allowed.
Each pending local suffix also completes to the independent Haskell result, and
NEWEPOCH forces its delivery: account balances, treasury, reserves and fees match
native applyRUpd. Epoch snapshot/parameter rotation is outside that delivery
assertion. Intermediate accumulated/remaining member prefixes are retained for
provenance, but the timing suite does not require identical internal partitions.
The existing explicit J3.6 mask removes only newly generated non-myopic likelihood
vectors; reward pot, all rewards, fees, reserves, treasury, accounts, state and
parameters remain compared. No full non-myopic equivalence claim is introduced.

`RewardSnapshotSuite` checkpoints genuine Haskell Conway prefixes through the
current native V5 format, restores their exact encoded pending state, and compares
completion to the independent Haskell result. The existing V5 fixture supplies
only the snapshot envelope; the captured reward inputs come from this oracle.
This is storage continuation, not a reference-node process restart or a chain replay.
The new timing suite checks epoch-boundary forced delivery from each pending local
prefix against the independently credited native accounts and pots. Existing native
ledger tests also cover the skipped-reward-start epoch behavior.

Decompressed SHA-256 (gzip mtime is zero):

- inputs.json: `6a2a1c731a085f547e1d5164ca46988e9b0bb5cd5a3105546adb8d858f237cd6`
- results.json: `899603a5a742144e5357e1edc7b59c8ce509b10d5d2e367c447733ef80741e65`

Reproduce using the [reward wrapper](../../../../../scripts/oracle/rewards/README.md)
and `storage/Test/runMain xyz.kd5ujc.scarab.storage.haskell.state.RewardTimingCases /evidence/inputs.json`.
Run the wrapper on those inputs, then compare the decoded JSON and the hashes above.
The native observer image ID for this execution is
`sha256:5c095f6581fe4fdc6b1da9b6d2c3dbcd7b835d9671a4450677e5472e72253a3e`.
The parent context manifest is
`sha256:66c862cc374749f3631e91114358f3c063a7b0ec144163e501f3d22ca47821ef`.
Inputs and outputs contain no keys or private chain data. Upstream licenses remain
in the fetched package distributions and [fixture notices](../../../../../fixtures/licenses).
