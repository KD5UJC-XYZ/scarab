// SPDX-License-Identifier: Apache-2.0
package lab.ledger

import lab.cbor.{Bytes, Cbor, Node, Value as V}
import lab.chain.{CardanoBlockIndex, CardanoBodyCommitment}

/** Opt-in extension of the cluster transfer comparison; historical profiles remain closed. */
object ClusterIntervalTransfer:
  val ProfileId = "conway-pv9-cluster-derived-key-comparison-v1"
  final class Bound private[ClusterIntervalTransfer] (
      val interval: ValidityInterval.Receipt,
      val blockHash: Bytes,
      val blockRawSha256: Bytes
  )
  final case class Receipt(
      bound: Bound,
      minimum: MinimumOutput.Receipt,
      transfer: ClusterTransfer.Receipt
  ):
    val profileId = ProfileId
    val fullLedgerValidated = false
    val referenceSnapshotAtomic = false

  private def array(node: Node): Either[String, Vector[Node]] = node.value match
    case V.Arr(xs) => Right(xs)
    case _         => Left("array required")

  /** Re-derive the transaction's actual inclusion slot from original blocks, anchored to the
    * supplied pre/post context. The post-state slot alone is never the validation slot.
    */
  def bind(
      context: ClusterTransfer.Context,
      original: Bytes,
      blocks: Vector[Bytes]
  ): Either[String, Bound] =
    for
      interval <- ValidityInterval.decode(original)
      _ <- Either.cond(
        blocks.nonEmpty && blocks.size <= 8 &&
          context.preSlot <= ValidityInterval.MaxSlot && context.postSlot <= ValidityInterval.MaxSlot,
        (),
        "bounded nonempty block range and uint64 context slots required"
      )
      root <- Cbor.decode(original)
      target <- array(root)
      indexed <- blocks.foldLeft[Either[String, Vector[CardanoBlockIndex.IndexedBlock]]](
        Right(Vector.empty)
      ) { (acc, raw) =>
        for
          previous <- acc
          block <- CardanoBlockIndex.inspect(raw)
          body <- CardanoBodyCommitment.inspect(raw).left.map(_.toString)
          _ <- Either.cond(
            block.era == "conway" && body.bodyCommitmentMatched,
            (),
            "Conway body commitment required"
          )
          parent = previous.lastOption.fold(context.preHash)(_.headerHash)
          slot = previous.lastOption.fold(context.preSlot)(_.slot)
          _ <- Either.cond(
            block.parentHash == parent && block.slot > slot &&
              previous.lastOption.forall(p => block.blockNo == p.blockNo + 1),
            (),
            "captured range continuity mismatch"
          )
        yield previous :+ block
      }
      _ <- Either.cond(
        indexed.last.headerHash == context.postHash && indexed.last.slot == context.postSlot,
        (),
        "captured range does not end at context post point"
      )
      included <- indexed
        .foldLeft[Either[String, Vector[(CardanoBlockIndex.IndexedBlock, Node, Node)]]](
          Right(Vector.empty)
        ) { (acc, block) =>
          for
            found <- acc
            root <- Cbor.decode(block.rawBytes)
            outer <- array(root)
            fields <- array(outer(1))
            bodies <- array(fields(1))
            witnesses <- array(fields(2))
            invalid <- array(fields(4))
            _ <- Either.cond(
              bodies.size == witnesses.size && invalid.isEmpty && fields(3).value == V.Map(
                Vector.empty
              ),
              (),
              "unsupported block transaction, auxiliary or invalid-index structure"
            )
          yield found ++ bodies.zip(witnesses).map((b, w) => (block, b, w))
        }
      _ <- Either.cond(included.size == 1, (), "closed range requires exactly one transaction")
      (block, body, witness) = included.head
      _ <- Either.cond(
        body.original == target(0).original && witness.original == target(1).original,
        (),
        "original transaction body/witness inclusion mismatch"
      )
      checked <- ValidityInterval.atSlot(interval, block.slot)
    yield new Bound(checked, block.headerHash, block.rawSha256)

  def compare(
      context: ClusterTransfer.Context,
      minimum: MinimumOutput.Parameters,
      pre: Bytes,
      post: Bytes,
      original: Bytes,
      blocks: Vector[Bytes]
  ): Either[String, Receipt] =
    def error(f: ClusterTransition.Failure): String = f match
      case ClusterTransition.Failure.Rejected(NativeSpending.Error.OutsideValidityInterval) =>
        "OutsideValidityIntervalUTxO"
      case other => other.toString
    for
      bound <- bind(context, original, blocks)
      _ <- Either.cond(bound.interval.satisfied, (), "OutsideValidityIntervalUTxO")
      env <- ClusterTransition.fromContext(context, minimum).left.map(error)
      before <- ClusterTransition
        .checkpoint(env, pre, context.feesBefore, context.preSlot, context.preHash)
        .left
        .map(error)
      applied <- ClusterTransition
        .applyTransaction(before, original, bound.interval.slot)
        .left
        .map(error)
      _ <- Either.cond(
        applied.candidate.nativeAdmission.isEmpty,
        (),
        "key-only comparison profile required"
      )
      _ <- ClusterTransition.compareReference(applied, post, context.feesAfter).left.map(error)
      tx = applied.candidate
    yield Receipt(
      bound,
      tx.minimum,
      ClusterTransfer.Receipt(
        tx.transactionId,
        tx.fee,
        applied.state.fees - before.fees,
        tx.spent,
        tx.created,
        before.size - tx.spent.size
      )
    )
