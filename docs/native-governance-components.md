# Normalized native governance component subset

`NativeGovernanceComponents` decodes a bounded supplied PV9 governance subset from
original epoch and derived seed bytes. It requires external SHA256 pins for both
complete originals and exactly fifteen component encodings. Every component must
equal its exact subtree in both originals. Native component serialization is a
re-encoding in general: this implementation deliberately supports only captures
whose encoded component bytes match those original spans. It does not generalize
that observed equality into a native serialization guarantee.

The checked object retains both originals, component originals/pins, epoch, current
accounts and DRep registrations, dormant count, present empty committee, empty
authorizations, empty constitution, proposal roots and the old completed DRep state.
Credential constructors remain distinct. Duplicate credential maps and reverse
delegator sets reject. Account votes naming DReps require current registrations;
each registered DRep's reverse set must exactly equal accounts voting for it.
Absent account votes and abstain/no-confidence votes are decoded explicitly.

## Current state and historical completed state

Current registrations come from certificate/voting state. The old completed snapshot
comes from governance's normalized DRep state; its four collections must be explicitly
empty. Current registrations are never substituted into that historical snapshot.
The native encoding completes the DRep pulser and loses its live cursor; no cursor
or authenticated history is reconstructed here.

The supported committee is present with an empty member map and a reduced unit
interval threshold. Absence is not treated as an empty committee. Constitution
requires an encoded empty URL, zero 32-byte hash and null script. Proposals and four
roots, committee authorizations, historical enacted/expired actions and withdrawals
must be explicitly empty; delayed ratification must be false. Current DRep anchors
must be explicitly absent. Expiry, deposit, dormant count and historical treasury
are decoded bounded unsigned values, not defaults inferred from the observed fixture.
Empty fields are produced only after checking the corresponding original encoding.

Outer current/previous parameter bytes and old enactment current/previous bytes are
retained separately. Only the 31-field record shape is checked here. Parameter values,
cost models and comparison policy belong to the separate parameter decoder. Outer
future parameters must encode `[0]` (NoPParamsUpdate); no missing or pending value
is silently treated as absent. No `G.Input` or runtime state is manufactured.

## Source paths and bounds

Zero-based record paths are:

| Value | Path |
| --- | --- |
| Certificate | NES[3][1][0] |
| Voting / delegation | certificate[0] / certificate[2] |
| Current DReps / authorizations / dormant | voting[0] / voting[1] / voting[2] |
| Accounts | delegation[0] |
| Governance | NES[3][1][1][3] |
| Proposals / committee / constitution | governance[0] / [1] / [2] |
| Current / previous / future parameters | governance[3] / [4] / [5] |
| Completed snapshot / ratify | governance[6][0] / governance[6][1] |
| Historical enactment | ratify[0] |

All enclosing record arities are checked. Original inputs are at most 1 MiB each,
components at most 64 KiB each, collections at most 4096 entries, CBOR depth at most
48 and CBOR items at most 200000 per original. Full consumption is mandatory. Floats
and other unsupported CBOR forms fail closed, including outside selected subtrees.
The immutable identity binds both original pins and all fifteen ordered component
pins. Pin authenticity is the caller's responsibility.

The nine encoding sources in `native-governance-source-pins.json` were checked
against retained immutable Conway 1.23.0.0, core 1.21.0.0 and Shelley 1.19.0.1 archives.
No dependency or native code was executed for that source verification.

## Actual retained input observation and limitations

The unchanged audited slot36/block1 epoch0 bundle has three current registrations,
each expiry1000/deposit0 with one matching reverse delegator, while the completed
historical snapshot has no DReps or distributions. Dormant and historical treasury
are zero. Outer current parameters differ from the old current; outer previous,
old current and old previous are byte-equal. These observations remain private
test inputs, not hardcoded replacements or public raw fixtures.

The first private harness incorrectly expected historical previous parameters to
differ too. The decoder succeeded; that harness assertion failed. The failed evidence
was retained, independent byte inspection established equality, and only the harness
expectation was corrected. No decoder guard or retained native byte was changed.

`componentDerivationChecked` and `voteReverseDelegationChecked` describe these local
checks only. Admission, runtime import, native conformance, authenticated snapshot,
live-cursor recovery, parameter semantics and whole-seed derivation remain false.
The native helper owns complete seed/stake/pot/snapshot derivation; this decoder
checks only the selected governance/certificate paths. Other bytes can differ without
these checks proving the full seed replacement relation.

This adds no CLI, live capture, protocol acquisition or full admission. The retained
v1 packet lacks protocol state and its 500-slot epoch geometry is unsuitable for the
intended admission path. A separately authorized fresh 1000-slot v2 acquisition and
all remaining composition checks are still required.

## Candidate verification

49 focused synthetic/regression tests passed (40 app, 9 ledger), including
11 new decoder tests, adjacent payload/Globals/composition tests and empty-governance
ledger checks. Formatting checks passed. Synthetic log SHA256:
`64927b03c031af2d384e67b1ca1a4b5ecfbb0672e0135036baa9a60c46c47521`.

One separate private actual-input test passed against the unchanged externally pinned
bundle. Actual-input log SHA256:
`370ec22d61fc7793ec0e5b86c723c36eb29cadf8877dcc01b5f017f308c91484`.
The source tested by both runs has SHA256:
`3582c4d8e3c00604096cf324b8e7e6494e3f9db3dca82896233a219cac71ced0`.
Both runs used private caches, network disabled, 2 CPU and 2 GiB with verified owned
container cleanup. Only the actual-input run mounted retained files, read-only. No
native executable ran. Public tests contain only generated synthetic data.

The first synthetic run exposed a test-fixture construction error for malformed
parent records before the decoder was reached. The corrected fixture now passes
freshly pinned malformed originals with valid component inputs and asserts the
production arity rejection. Both that failure and the earlier private harness
expectation failure were preserved. No production guard changed for either fix.

## Integration with the audited parameter-role extension

The combined integration passed 110 Scala tests (89 app, 21 ledger) and formatting
checks. This includes separate opt-in checks of actual governance originals and
actual parameter originals, the public synthetic mutation suites, and default
node/coverage safeguards. The source remained byte-identical to the reviewed decoder;
the existing parameter decoder remains single-source. Combined log SHA256:
`365be24e9695b0d7261591382d8ce69e9fd763c99780501793a14fc6f5401932`.
Private input mounts were read-only, network disabled, 2 CPU/2 GiB and a private
cache; owned container cleanup was verified. That integration left the parameter-role native differential unexecuted; its subsequent
finite synthetic-epoch result is recorded in [audited governance roles](audited-governance-roles.md#authorized-native-differential-execution). These checks do not establish full seed derivation,
protocol-state acquisition, native equivalence, evaluation-context validity or runtime
admission. No raw private bundle or private actual-input harness is published.
