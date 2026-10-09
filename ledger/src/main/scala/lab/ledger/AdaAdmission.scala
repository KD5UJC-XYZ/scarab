// SPDX-License-Identifier: Apache-2.0
package lab.ledger

import lab.cbor.{Bytes, Cbor, Node, Value as V}
import lab.submission.SignedTransaction

/** Pure scoped validation. P is the integration owner's complete immutable StatePin. The owner must
  * capture (pin, view) under its common mutation gate.
  */
object AdaAdmission:
  val ProfileId = "isolated-conway-pv9-ada-vkey-v1"
  enum Failure:
    case Identity(error: SignedTransaction.Error)
    case Unsupported(detail: String)
    case Ledger(error: ClusterTransition.Failure)

  final case class FeeReceipt(supplied: BigInt, minimum: BigInt, memoBytes: BigInt)
  final class Candidate[P] private[AdaAdmission] (
      val transaction: SignedTransaction,
      val pin: P,
      val spent: Set[TxIn],
      val fee: FeeReceipt,
      val minimumOutput: MinimumOutput.Receipt,
      val ledgerStateId: Bytes,
      val environmentId: Bytes,
      val validationSlot: BigInt
  ):
    val profileId = ProfileId
    val fullLedgerValidated = false

  private def outputs(nodes: Vector[Node]): Either[Failure, Unit] =
    nodes.foldLeft[Either[Failure, Unit]](Right(()))((acc, node) =>
      acc.flatMap(_ =>
        NativeSpending.output(node, false).left.map(Failure.Unsupported.apply).map(_ => ())
      )
    )

  /** Whitelist before prepare: native-script-capable preflight must never widen this profile. */
  def prepare[P](
      pin: P,
      view: ClusterTransition.State,
      original: Bytes
  ): Either[Failure, Candidate[P]] =
    for
      identity <- SignedTransaction.checked(original).left.map(Failure.Identity.apply)
      root <- Cbor
        .decode(original, Cbor.Limits(65536, 16, 65536, 65536))
        .left
        .map(Failure.Unsupported.apply)
      parts <- root.value match
        case V.Arr(Vector(body, witnesses, validity, auxiliary)) =>
          Right((body, witnesses, validity, auxiliary))
        case _ => Left(Failure.Unsupported("four-field envelope required"))
      (body, witnesses, validity, auxiliary) = parts
      _ <- Either.cond(
        validity.value == V.Bool(true) && auxiliary.value == V.Null,
        (),
        Failure.Unsupported("phase-two false or auxiliary data")
      )
      fields <- body.value match
        case V.Map(fs)
            if fs.forall((k, _) =>
              Set(V.UInt(0), V.UInt(1), V.UInt(2), V.UInt(3), V.UInt(8)).contains(k.value)
            ) =>
          Right(fs)
        case _ => Left(Failure.Unsupported("body field outside ADA transfer profile"))
      _ <- witnesses.value match
        case V.Map(fs) if fs.size == 1 && fs.head._1.value == V.UInt(0) => Right(())
        case _ => Left(Failure.Unsupported("only vkey witnesses supported"))
      outs <- fields.find(_._1.value == V.UInt(1)).map(_._2.value) match
        case Some(V.Arr(xs)) => Right(xs)
        case _               => Left(Failure.Unsupported("outputs array required"))
      _ <- outputs(outs)
      refs <- fields.find(_._1.value == V.UInt(0)).map(_._2) match
        case Some(node) =>
          val value = node.value match
            case V.Tag(n, inner) if n == 258 => inner.value
            case other                       => other
          value match
            case V.Arr(xs) =>
              xs.foldLeft[Either[Failure, Set[TxIn]]](Right(Set.empty)) { (acc, n) =>
                for
                  seen <- acc
                  ref <- n.value match
                    case V.Arr(Vector(id, index)) =>
                      (id.value, index.value) match
                        case (V.ByteString(b), V.UInt(i)) =>
                          TxIn.create(b, i).left.map(e => Failure.Unsupported(e.toString))
                        case _ => Left(Failure.Unsupported("input shape"))
                    case _ => Left(Failure.Unsupported("input shape"))
                yield seen + ref
              }
            case _ => Left(Failure.Unsupported("input set required"))
        case _ => Left(Failure.Unsupported("inputs required"))
      snapshot <- NativeSpending
        .snapshot(view.outputMap)
        .left
        .map(e => Failure.Unsupported(e.toString))
      _ <- outputs(refs.toVector.flatMap(snapshot.get))
      prepared <- ClusterTransition
        .prepare(view, original, view.slot)
        .left
        .map(Failure.Ledger.apply)
      memo <- FeeSize
        .componentSize(body.original.size, witnesses.original.size)
        .left
        .map(e => Failure.Unsupported(e.toString))
      parameters = view.environment.feeParameters
    yield new Candidate(
      identity,
      pin,
      prepared.spent,
      FeeReceipt(prepared.fee, memo * parameters.feePerByte + parameters.feeFixed, memo),
      prepared.minimum,
      view.id,
      view.environment.id,
      view.slot
    )
