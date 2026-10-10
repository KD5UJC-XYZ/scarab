// SPDX-License-Identifier: Apache-2.0
package lab.ledger

import lab.Blake2b
import lab.cbor.{Bytes, Cbor, Node, Value as V}
import lab.plutus.PlutusExecution
import lab.submission.SignedTransaction
import lab.witness.{StrictEd25519, VerificationResult}
import scala.util.control.NonFatal

/** Closed phase-one facts. Context/integrity and phase two must still succeed before admission. */
object PlutusSpending:
  enum Failure:
    case Malformed(detail: String)
    case Unsupported(detail: String)
    case Rejected(detail: String)
    case InternalFailure(kind: String)

  private final case class Failed(error: Failure) extends RuntimeException
  private def reject(detail: String): Nothing = throw Failed(Failure.Rejected(detail))
  private def malformed(detail: String): Nothing = throw Failed(Failure.Malformed(detail))
  private def unsupported(detail: String): Nothing = throw Failed(Failure.Unsupported(detail))
  private def require(ok: Boolean, detail: String): Unit = if !ok then reject(detail)
  private def scope(ok: Boolean, detail: String): Unit = if !ok then unsupported(detail)
  final class Prepared private[PlutusSpending] (
      private[ledger] val source: ClusterTransition.State,
      val transaction: SignedTransaction,
      val contextInput: PlutusContextInput,
      val spent: Set[TxIn],
      val collateral: Set[TxIn],
      val payout: Bytes,
      val fee: PlutusFees.Receipt,
      val minimumOutput: MinimumOutput.Receipt,
      val ledgerStateId: Bytes,
      val environmentId: Bytes,
      val validationSlot: BigInt
  )
  private def get[A](e: Either[?, A]): A =
    e.fold(e => reject(e.toString), identity)
  private def arr(n: Node, size: Int): Vector[Node] = n.value match
    case V.Arr(xs) if xs.size == size => xs
    case _                            => unsupported("fixed array cardinality required")
  private def uint(n: Node): BigInt = n.value match
    case V.UInt(x) if x >= 0 && x <= (BigInt(1) << 64) - 1 => x
    case _                                                 => malformed("uint64 required")
  private def bytes(n: Node, size: Int): Bytes = n.value match
    case V.ByteString(b) if b.size == size => b
    case _                                 => malformed("fixed byte width required")
  private def tagged(n: Node): Node = n.value match
    case V.Tag(t, inner) if t == 258 => inner
    case _                           => unsupported("tag258 set required")
  private def fields(n: Node, allowed: Set[BigInt], required: Set[BigInt]): Map[BigInt, Node] =
    val pairs = n.value match
      case V.Map(xs) => xs.map((k, v) => uint(k) -> v)
      case _         => malformed("map required")
    if pairs.map(_._1).distinct.size != pairs.size then malformed("duplicate semantic key")
    val result = pairs.toMap
    scope(result.keySet.subsetOf(allowed), "unsupported fields")
    if !required.subsetOf(result.keySet) then malformed("missing required fields")
    result
  private def ref(n: Node): TxIn =
    val xs = arr(arr(tagged(n), 1).head, 2)
    get(TxIn.create(bytes(xs(0), 32), uint(xs(1))))

  def prepare(
      view: ClusterTransition.State,
      transaction: SignedTransaction,
      environment: PlutusEnvironment,
      validationSlot: BigInt
  ): Either[Failure, Prepared] =
    try
      require(
        view != null && transaction != null && environment != null,
        "complete confirmed context required"
      )
      require(
        view.environment.id == environment.id && view.environment.plutus.contains(environment),
        "explicit matching Plutus state profile required"
      )
      require(
        validationSlot != null && validationSlot >= view.slot &&
          validationSlot <= (BigInt(1) << 64) - 1,
        "validation slot range"
      )
      val root = arr(
        Cbor
          .decode(transaction.original, Cbor.Limits(65536, 32, 4096, 4096))
          .fold(e => malformed(e.toString), identity),
        4
      )
      scope(
        root(2).value == V.Bool(true) && root(3).value == V.Null,
        "successful envelope with null auxiliary required"
      )
      val body = fields(root(0), Set(0, 1, 2, 3, 8, 11, 13), Set(0, 1, 2, 11, 13))
      val witnesses = fields(root(1), Set(0, 5, 7), Set(0, 5, 7))
      val lower = body.get(8).map(uint); val upper = body.get(3).map(uint)
      require(
        lower.forall(_ <= validationSlot) && upper.forall(validationSlot < _),
        "transaction outside inclusive-lower exclusive-upper validity interval"
      )
      val spent = ref(body(0)); val collateral = ref(body(13))
      require(spent != collateral, "ordinary/collateral overlap")
      val snapshot = get(PlutusOutput.snapshot(view.outputMap, environment.networkId))
      val consumed = snapshot.outputs.getOrElse(
        spent,
        reject("missing spending input")
      )
      val security = snapshot.outputs.getOrElse(
        collateral,
        reject("missing collateral input")
      )
      scope(
        consumed.kind == 7 && consumed.datum.nonEmpty && security.kind == 6 && security.datum.isEmpty,
        "one inline script input and datumless enterprise key collateral required"
      )
      val outputNode = arr(body(1), 1).head
      val output = PlutusOutput
        .decode(outputNode.original, environment.networkId)
        .fold(unsupported, identity)
      scope(output.kind == 6 && output.datum.isEmpty, "single enterprise key payout required")
      val fee = uint(body(2))
      require(consumed.coin == output.coin + fee, "ADA conservation")
      val witness = arr(arr(tagged(witnesses(0)), 1).head, 2)
      val publicKey = bytes(witness(0), 32); val signature = bytes(witness(1), 64)
      require(
        Blake2b.hash224.hash(publicKey) == security.paymentCredential,
        "collateral owner witness missing"
      )
      require(
        get(StrictEd25519.verify(publicKey, signature, transaction.transactionId)) ==
          VerificationResult.SignatureVerified,
        "collateral signature rejected"
      )
      val p = environment.parameters
      val minimum = MinimumOutput.Receipt(
        Vector(
          MinimumOutput.Output(
            0,
            output.original,
            output.coin,
            (BigInt(160) + output.original.size) * p.minimumOutput.coinsPerUTxOByte
          )
        )
      )
      require(minimum.satisfied, "minimum payout coin")
      require(get(Cbor.encode(V.UInt(output.coin))).size <= p.maxValueSize, "maximum value size")
      val fees = get(
        PlutusFees.check(
          p.linear,
          p.execution,
          transaction.originalBody.size,
          transaction.originalWitnesses.size,
          fee,
          PlutusExecution.ProfileBudget,
          security.coin,
          1
        )
      )
      val input = PlutusContextInput(
        transaction,
        Vector(PlutusContextInput.ResolvedInput(spent.id, spent.index, consumed.original)),
        Vector(
          PlutusContextInput.ResolvedInput(collateral.id, collateral.index, security.original)
        ),
        environment.time
      )
      Right(
        new Prepared(
          view,
          transaction,
          input,
          Set(spent),
          Set(collateral),
          output.original,
          fees,
          minimum,
          view.id,
          environment.id,
          validationSlot
        )
      )
    catch
      case Failed(error) => Left(error)
      case NonFatal(e)   => Left(Failure.InternalFailure(e.getClass.getName))
