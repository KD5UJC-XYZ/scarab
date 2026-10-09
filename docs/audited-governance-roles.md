# Audited cost models and historical governance parameter roles

This incremental candidate starts from committed main `0944c9e`. It is a bounded
supplied-state compatibility change, with no seed admission, CLI, live cursor, native
validation, or script-evaluation authority.

## Cost-model payloads

The checked Conway PV9 parameter bridge retains its 31-field original record and exact
field spans. Field 15 accepts the empty map or any subset of language keys 0, 1 and 2,
with respectively 166, 175 and 251 signed Int64 values. It exposes immutable decoded
vectors without re-encoding the supplied payload. Unknown or duplicate keys, wrong
lengths, non-integers and signed overflow reject. All existing payload size, depth and whole-source hash checks remain. Generic
`ConwayEmptyGovernance.payload` retains its canonical-definite requirement. A separate
parameter-specific constructor permits indefinite arrays only for the recognized
field-15 cost vectors, with exact minimal signed scalar bytes and a closing break.
All other fields, array/map headers and map ordering retain canonical checks.
The original bytes are preserved, never normalized.

Pinned core 1.21.0.0 `Plutus/CostModels.hs` records these initial parameter counts and
serializes language keys to signed integer vectors. Binary 1.9.1.0 `Encoding/EncCBOR.hs` delegates lists to `encodeList`;
`Encoding/Encoder.hs` lines 493–508 emits indefinite lists over 23 entries at PV>=2.
The audited cost vectors have exactly that encoding. Its PV9 native decoder is more
permissive about unknown languages and lengths; this finite Scala subset intentionally
does not claim all native cost-model validity or evaluation-context construction.

The read-only audited projection has previous language lengths 0:166 and 2:251, current
lengths 0:166, 1:175 and 2:251. Its only differing original parameter field is 15. The
optional evidence test is enabled in this candidate's Docker run; no actual cluster
seed, credentials, keys or complete projection is added to Git.

## Historical enactment and outer temporal roles

The original case, where completed old enactment current equals outer current, remains
unchanged. One additional case permits completed old enactment current equal to outer
previous, only when future parameters explicitly say `NoUpdate`. Old enactment previous
must still equal outer previous. Both outer records must be complete checked 31-field
PV9.0 arrays whose sole difference is the cost-model map at field 15.

All prior empty-action, completed-pulser, committee, constitution, root, zero enactment
treasury/withdrawal and deposit checks remain. PotentialNone, pending updates, different
non-cost fields, different protocol versions and unrelated historical roles do not gain
support. The pure governance model checks this temporal structural case; the typed
parameter bridge additionally checks full selected field shapes and cost-model bounds.

Pinned Conway 1.23.0.0 `Rules/Epoch.hs` lines 323–325 installs
`nextEpochPParams govState0` and copies the outer current parameters into previous.
Core 1.21.0.0 `State/Governance.hs` lines 121–133 selects outer current for
`NoPParamsUpdate`. It does not choose old enactment current. The Scala output therefore
uses outer current in both new roles while preserving the original old completed
enactment in `Applied.before` and its identity. It never substitutes that input.

## Verification and native packet

The final offline Docker run passed **45 app tests and 21 ledger tests**, including
the actual audited parameter bytes, all new byte-form rejection tests, both temporal
composition cases and adjacent existing regressions. It took 24.071 seconds using
at most two CPUs and 2 GiB memory, no network, and a private build/cache. The owned
container was confirmed removed. Formatting ran before those tests, and only the
five owned Scala files were copied back. Five Python packet tests and read-only
inspection of the complete pinned audited input also passed.

Private evidence is retained outside Git, including command/image/receipt/log, audited input hash, packet checks and final
formatted source hashes. The earlier actual-input test rejected the unhandled native
indefinite vectors; the narrow encoding exception above resolves that specific
finding without normalizing the bytes or loosening generic payload validation. The prepared packet under
`reference/audited-governance-roles-diff` exercises native parameter selection and the
old-enactment distinction independently. It is not executed by Scala unit tests.
Native build/execution requires separate review; none is authorized or claimed here.

## Main integration verification

The standalone integration passed 98 Scala tests (77 app, 21 ledger) and formatting
checks, including the opt-in audited original parameter input and default node/coverage
safeguards. Five pure Python packet tests and a read-only audited-input inspection
also passed. All five Scala files and native packet source match the approved worker.
Scala log SHA256: `522bcbf86524cd5d75c073134dce68a65fd3ad22418042f45230b5c00e108c8e`.
The Scala run used network disabled, 2 CPU/2 GiB, a private cache and the original
projection mounted read-only; owned container cleanup was verified. The native
differential remains source-only and unexecuted. No native equivalence or cost-model
evaluation-context validity is claimed. The historical-role exception applies only
to explicit NoUpdate, not PotentialNone, with the exact field-15-only distinction.
