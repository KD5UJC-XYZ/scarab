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
Native execution was initially deferred; its subsequently authorized result is recorded below.

## Main integration verification

The standalone integration passed 98 Scala tests (77 app, 21 ledger) and formatting
checks, including the opt-in audited original parameter input and default node/coverage
safeguards. Five pure Python packet tests and a read-only audited-input inspection
also passed. All five Scala files and native packet source match the approved worker.
Scala log SHA256: `522bcbf86524cd5d75c073134dce68a65fd3ad22418042f45230b5c00e108c8e`.
The Scala run used network disabled, 2 CPU/2 GiB, a private cache and the original
projection mounted read-only; owned container cleanup was verified. That Scala integration did not execute the native differential; the later authorized
run is recorded below. No native equivalence or cost-model
evaluation-context validity is claimed. The historical-role exception applies only
to explicit NoUpdate, not PotentialNone, with the exact field-15-only distinction.

## Authorized native differential execution

After packet and resource-slot approval, the revised existing offline runner passed on
2026-10-09 against source commit `7da4ebd71bd13246e90ff079535e128a53aa7ee5`.
Both `exact-current` and `previous-costs-only` applied native NEWEPOCH and matched
the unchanged independent expectations for all four full parameter objects/encodings
before and after the boundary. `reject-non-cost-change` returned the explicit
profile-rejection tag without executing native STS. No semantic expectation changed.

The native reader checked exact input decode/re-encode equality. Previous parameter
SHA256 was `0f6d70064c39fb492af2f456aa87e3ce44195bb791da4a74a54d507bb01899ef`;
current was `75146abbc571a0a633ea1b62dc34d4e0c85164f29a1492ecf20f1929070d9600`.
Result SHA256: `8bc636e667c89fadefad6f9f5ae5aed72b930d3cfe8268a29bad7acf2293ffd7`.
Binary SHA256: `d0e818c629c949e4e047cf526caf59ab8a42b1efe552f7cb0b97c507441c5715`.

Complete execution evidence remains private outside Git.
The approved packet-pin SHA256 was
`329ec419a62375a2c9216bbac61043df75601699b31e694b08cdc2b75489a1cf`.
The wrapper checked the complete 379-unit nonlocal plan and selected 235-unit closure,
required a local-only dry run, and verified containment before execution: no network,
two CPUs, 2 GiB memory and total memory+swap, read-only root, dropped capabilities,
no-new-privileges, and private writable cache. Owned container absence and cleanup
were confirmed; only evidence remains.

The 300-second attempt used **16.036323594 seconds including cleanup**, leaving
**295.198647159 seconds** of the shared native budget. Telemetry recorded 10,138
completed file copies and 2,633,228,852 bytes; store copy took 2.497 seconds, package
copy 1.519 seconds, and 30 disk scans took 2.822 seconds total (maximum 0.107 seconds).

The earlier attempt remains preserved in separate private evidence: it spent 285.317239315
seconds in private copying/scanning, issued no Docker commands, and cleaned up.
The reviewed retry fixed completion-based scan throttling and per-directory
classification, with 17 static wrapper tests; it neither pruned caches nor used
shared writable hardlinks. The earlier evidence and original approved wrapper remain
unchanged.

This establishes the finite native parameter-selection/encoding differential in the
synthetic epoch fixture. It does not establish full audited-seed execution, cost-model
evaluation-context validity, general governance, live pulser recovery, or native
seed/runtime admission. Protocol switches and `/runwork` permissions were untouched.

## Curated default regression

`app/src/test/resources/audited-governance-roles/native-role-pattern.json` is derived
from the hash-pinned native result only after exact comparison with the independently
extracted original parameter encodings. It replaces each complete parameter encoding
with P or C, retaining their SHA256s, byte lengths, case IDs, status and temporal roles.
No raw parameter vector, private bundle, log or binary is published.

The default Scala regression applies the existing supplied synthetic governance model
using generated parameters and compares the before/after role pattern with this
recorded native pattern. This is a temporal-selection regression, not a default
replay of native parameter bytes or a cost-model evaluation test. The native evidence
separately establishes full original-encoding equality for the two approved synthetic
NEWEPOCH cases. The third case is rejected by a finite profile before native STS;
it is not evidence of native consensus rejecting a broader parameter change.

The focused default verification passed **15 Scala tests** (five role-composition
tests and ten parameter-payload tests), plus formatting checks. It used an offline
2 CPU/2 GiB Docker container with a private cache, no private fixture mounts and
confirmed cleanup. No native executable was rerun. Test log SHA256:
`46b86e515fa6c1480aa87d48401e0531ccc74a157946b9535cda5eb985bfb903`.
