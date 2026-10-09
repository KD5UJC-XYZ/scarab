# Native-script submission contract v1

Status: native ingress is implemented and the signature-only isolated acceptance
case passed. See [implementation and evidence](native-submission-implementation.md).
Existing ADA-only admission remains the default; wider native reference matrices
and exact-slot negative-oracle agreement remain pending.
The executable remains Test-only and app-private, with loopback ingress and a
volatile pool; this is not a production node API or full ledger validator.

## Exact shared interfaces

`core/src/main/scala/lab/submission/AdmissionProfile.scala` defines the closed enum
`AdmissionProfile.AdaVkey` (`isolated-conway-pv9-ada-vkey-v1`) and
`AdmissionProfile.NativeScript` (`isolated-conway-pv9-ada-native-v1`). A profile is
fixed for an owner lifetime. StatePin accepts only these IDs and compares the
profile as part of the complete pin. The owner default stays AdaVkey.

`ledger/src/main/scala/lab/ledger/ScopedAdmission.scala` owns:

```scala
enum Failure:
  case Identity(error: SignedTransaction.Error)
  case Unsupported(detail: String)
  case Ledger(error: ClusterTransition.Failure)

private[ledger] def checked[P](
    profile: AdmissionProfile,
    pin: P,
    view: ClusterTransition.State,
    identity: SignedTransaction
): Either[Failure, Candidate[P]]
```

Candidate has a private constructor and exposes `transaction`, `pin`, `profile`,
`profileId`, `spent`, `fee` (supplied/minimum/memoBytes), `minimumOutput`,
`ledgerStateId`, `environmentId`, `validationSlot`,
`nativeAdmission: Option[NativeSpending.Admission]` and `fullLedgerValidated=false`.
It exposes no post-state. `checked` calls ClusterTransition.prepare once, checks
whether script credentials match the selected profile and constructs receipts.
The imported pure worker also provides `NativeAdmission.check(view, original)`
with private-constructor `Checked` evidence. Native `prepare` binds that receipt
through `ScopedAdmission.bindNative(pin, view, checked)`, which verifies its
ledger/environment/slot/profile bindings and does not repeat ledger validation.
Call it only after the complete profile whitelist. Never construct a candidate
directly, duplicate validation, or commit a hypothetical chain state.

AdaAdmission retains its public prepare signature; Failure, Candidate and
FeeReceipt are aliases of the shared types. Its whitelist remains unchanged.

## Pure validator worker ownership

Own only new `ledger/src/main/scala/lab/ledger/NativeAdmission.scala`, its focused
test suite and deterministic synthetic fixture helpers. Do not edit shared
interfaces, AdaAdmission, AdaPool, app, network or controllers.

Provide exactly:

```scala
object NativeAdmission:
  val ProfileId = AdmissionProfile.NativeScript.id
  def prepare[P](pin: P, view: ClusterTransition.State, original: Bytes)
      : Either[ScopedAdmission.Failure, ScopedAdmission.Candidate[P]]
```

Use SignedTransaction.checked first. Enforce body keys {0,1,2,3,8}, witness keys
{0,1}, true validity/null auxiliary data, confirmed coin-only inputs, at least
one enterprise script input (kind7), optional key inputs (kinds0/6), and only
key-payment outputs (kinds0/6), all testnet. The final adapter may either call
`ScopedAdmission.checked(AdmissionProfile.NativeScript, pin, view, identity)`
after the whitelist, or bind the existing validated `Checked` receipt as above.
It must not perform ledger validation twice.
Reuse native predicate/witness/credential validation through ClusterTransition.
Plutus, mint/multiasset, datum, reference inputs/scripts, collateral, governance,
certificates, withdrawals, auxiliary data and required-signers remain unsupported.

Preserve existing bounds: 65536 total bytes,128 inputs/outputs,128 vkey entries,
32 script entries before deduplication,1024 decoded items per script and16 AST
depth. The enclosing transaction depth gate can be tighter; test actual envelope
limits, malformed children and limit+1. No unsafeRunSync.

Test signature/all/any/threshold/timelock positives; missing/wrong/extra scripts,
missing key-input authorization, invalid signatures, unsatisfied predicates,
validity edges, hash-sensitive original encoding, duplicate witnesses, fee and
minimum-output boundaries, resource limits and every unsupported field. Keep
same-body controls for witness-only negatives; re-sign body mutations. Assert
prepare preserves confirmed state and the ADA gate still rejects native cases.

## Integration and fixture ownership

Main owns the shared models, owner, pool, service, HTTP, relay integration and
final live slot. Admission and revalidation select the same fixed profile;
candidate/pin/profile mismatch fails closed. Original-byte leases, generation
fencing and all resource/lifetime limits remain intact.

The fixture worker owns separate fixture/bootstrap/reference helpers and pure
guards. Start from one confirmed enterprise script UTxO in an exact native
bootstrap; any funding is an explicit fixture-only phase before capture. Retain
funding originals, complete post-funding UTxO, fee pot and full point. Prove the
kind7 bootstrap offline before requesting a bounded live slot through the parent.
The transaction under acceptance test must enter Scala HTTP and Scala relay;
reference CLI submission cannot stand in for ingress acceptance.

Reference comparisons need accepted and rejected controls; local Unsupported or
resource rejection does not imply reference-ledger invalidity. Main requires
follower inclusion, original body/witness comparison, pool removal, held-state
exact endpoint comparison and owned cleanup. Script-output creation and Plutus
remain separate later profiles. Disposable isolated local tests only: no public
network transactions, real funds, new credentials or unrelated container changes.

## Test-only executable hooks for fixture integration

Main owns these existing Test-only classes. `lab.AdaSubmissionMain` keeps the five
existing arguments and accepts an optional sixth `PROFILE_ID`; the external
`lab.AdaSubmissionClientMain` keeps its three existing arguments and accepts an
optional fourth `PROFILE_ID`. Omission selects the unchanged ADA profile. Native
fixtures must pass `isolated-conway-pv9-ada-native-v1` to both and put that value
in `submission/descriptor.json` as `profileId`. The bootstrap-ready, client result
and final Scala result carry that profile; the final result additionally records
`nativeScriptSubmission=true`. Existing ADA result schema names and coin-only
submission flags remain compatible. Native controllers must verify the profile
and native flag explicitly, not infer native acceptance from the ADA flags.

The controller still owns bounded execution, exact namespace/network and resource
checks, original-span comparisons and cleanup. No live allocation is implied by
these hooks; main coordinates the single live slot through the parent.

## Funded initial-state accounting

The native fixture starts from a confirmed script UTxO created by a separate,
one-use reference-only funding phase. Explicit `AdmissionProfile.NativeScript`
bootstrap permits bounded fees and checks `UTxO coin + reserves + fees = supply`.
Treasury, deposits and donations retain the initial profile's zero restrictions.
The default ADA bootstrap still requires zero fees and preserves its identity
recipe. Native bootstrap has a distinct profile-bound identity. All original
source, acquisition, governance and complete-state checks remain in force.

The public CLI fixture accepts `inlineDatumRaw` only when absent or JSON null,
matching CLI 11.2.3.0's coin-only output representation. Any non-null value remains
unsupported; this does not enable inline datums.
