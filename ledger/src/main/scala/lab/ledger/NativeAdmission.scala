// SPDX-License-Identifier: Apache-2.0
package lab.ledger

import lab.cbor.{Bytes, Cbor, Node, Value as V}
import lab.submission.{AdmissionProfile, SignedTransaction}

/** Pure, opt-in native profile proof. The app's shared candidate factory must bind this receipt to
  * its complete state pin and closed profile. This object neither owns that factory nor publishes
  * the hypothetical ClusterTransition state.
  */
object NativeAdmission:
  val ProfileId = AdmissionProfile.NativeScript.id
  import AdaAdmission.{Failure, FeeReceipt}

  final class Checked private[NativeAdmission] (
      val transaction: SignedTransaction,
      val spent: Set[TxIn],
      val fee: FeeReceipt,
      val minimumOutput: MinimumOutput.Receipt,
      val native: NativeSpending.Admission,
      val ledgerStateId: Bytes,
      val environmentId: Bytes,
      val validationSlot: BigInt
  ):
    val profileId = ProfileId
    val credentialBound = true
    val fullLedgerValidated = false

  private def collection(node: Node, maximum: Int): Either[Failure, Unit] =
    val value = node.value match
      case V.Tag(tag, inner) if tag == 258 => inner.value
      case other                           => other
    value match
      case V.Arr(xs) if xs.nonEmpty && xs.size <= maximum => Right(())
      case _ =>
        Left(
          Failure.Unsupported(
            "present witness collection must be nonempty, bounded and optionally tagged 258"
          )
        )

  def check(view: ClusterTransition.State, original: Bytes): Either[Failure, Checked] =
    for
      identity <- SignedTransaction.checked(original).left.map(Failure.Identity.apply)
      _ <- Either.cond(view != null, (), Failure.Unsupported("confirmed ledger view required"))
      root <- Cbor
        .decode(original, Cbor.Limits(65536, 16, 65536, 65536))
        .left
        .map(Failure.Unsupported.apply)
      parts <- root.value match
        case V.Arr(Vector(body, witnesses, valid, aux))
            if valid.value == V.Bool(true) && aux.value == V.Null =>
          Right((body, witnesses))
        case _ => Left(Failure.Unsupported("true validity and null auxiliary data required"))
      (body, witnesses) = parts
      fields <- body.value match
        case V.Map(fs)
            if fs.forall((k, _) =>
              Set(V.UInt(0), V.UInt(1), V.UInt(2), V.UInt(3), V.UInt(8)).contains(k.value)
            ) =>
          Right(fs)
        case _ => Left(Failure.Unsupported("body field outside native ADA transfer profile"))
      witnessFields <- witnesses.value match
        case V.Map(fs) if fs.forall((k, _) => Set(V.UInt(0), V.UInt(1)).contains(k.value)) =>
          Right(fs)
        case _ => Left(Failure.Unsupported("only vkey and native script witnesses supported"))
      _ <- witnessFields.foldLeft[Either[Failure, Unit]](Right(())) { case (acc, (key, node)) =>
        acc.flatMap(_ =>
          collection(
            node,
            if key.value == V.UInt(0) then NativeScript.MaxWitnesses
            else NativeScriptWitnesses.MaxScripts
          )
        )
      }
      outs <- fields.find(_._1.value == V.UInt(1)).map(_._2.value) match
        case Some(V.Arr(xs)) if xs.size <= 128 => Right(xs)
        case _ => Left(Failure.Unsupported("at most 128 key-payment ADA outputs required"))
      _ <- outs.foldLeft[Either[Failure, Unit]](Right(()))((acc, out) =>
        acc.flatMap(_ =>
          NativeSpending.output(out, false).left.map(Failure.Unsupported.apply).map(_ => ())
        )
      )
      prepared <- ClusterTransition
        .prepare(view, original, view.slot)
        .left
        .map(Failure.Ledger.apply)
      native <- prepared.nativeAdmission.toRight(
        Failure.Unsupported("at least one confirmed enterprise native-script input required")
      )
      parameters = view.environment.feeParameters
    yield new Checked(
      identity,
      prepared.spent,
      FeeReceipt(
        prepared.fee,
        native.memoSize * parameters.feePerByte + parameters.feeFixed,
        native.memoSize
      ),
      prepared.minimum,
      native,
      view.id,
      view.environment.id,
      view.slot
    )

  /** Bind the worker's immutable validation receipt to the integration owner's complete pin. check
    * performs ledger validation once; binding does not repeat or publish that transition.
    */
  def prepare[P](
      pin: P,
      view: ClusterTransition.State,
      original: Bytes
  ): Either[ScopedAdmission.Failure, ScopedAdmission.Candidate[P]] =
    check(view, original).flatMap(ScopedAdmission.bindNative(pin, view, _))
