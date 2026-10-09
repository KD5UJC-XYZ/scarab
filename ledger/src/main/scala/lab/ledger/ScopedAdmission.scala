// SPDX-License-Identifier: Apache-2.0
package lab.ledger

import lab.submission.{AdmissionProfile, SignedTransaction}
import lab.cbor.Bytes

/** Shared receipt, never a hypothetical chain state. Profile gates call checked after their
  * whitelist; only ClusterTransition prepares the ledger result.
  */
object ScopedAdmission:
  enum Failure:
    case Identity(error: SignedTransaction.Error)
    case Unsupported(detail: String)
    case Ledger(error: ClusterTransition.Failure)

  final case class FeeReceipt(supplied: BigInt, minimum: BigInt, memoBytes: BigInt)
  final class Candidate[P] private[ScopedAdmission] (
      val transaction: SignedTransaction,
      val pin: P,
      val profile: AdmissionProfile,
      val spent: Set[TxIn],
      val fee: FeeReceipt,
      val minimumOutput: MinimumOutput.Receipt,
      val ledgerStateId: Bytes,
      val environmentId: Bytes,
      val validationSlot: BigInt,
      val nativeAdmission: Option[NativeSpending.Admission]
  ):
    val profileId = profile.id
    val fullLedgerValidated = false

  private[ledger] def checked[P](
      profile: AdmissionProfile,
      pin: P,
      view: ClusterTransition.State,
      identity: SignedTransaction
  ): Either[Failure, Candidate[P]] =
    for
      prepared <- ClusterTransition
        .prepare(view, identity.original, view.slot)
        .left
        .map(Failure.Ledger.apply)
      _ <- Either.cond(
        (profile == AdmissionProfile.NativeScript) == prepared.nativeAdmission.nonEmpty,
        (),
        Failure.Unsupported("consumed credentials do not match selected admission profile")
      )
      memo <- FeeSize
        .componentSize(identity.originalBody.size, identity.originalWitnesses.size)
        .left
        .map(e => Failure.Unsupported(e.toString))
      parameters = view.environment.feeParameters
    yield new Candidate(
      identity,
      pin,
      profile,
      prepared.spent,
      FeeReceipt(prepared.fee, memo * parameters.feePerByte + parameters.feeFixed, memo),
      prepared.minimum,
      view.id,
      view.environment.id,
      view.slot,
      prepared.nativeAdmission
    )
