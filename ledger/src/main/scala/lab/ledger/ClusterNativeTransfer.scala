// SPDX-License-Identifier: Apache-2.0
package lab.ledger

import lab.cbor.Bytes

/** Captured, restricted ADA native-script spending comparison, not consensus/full ledger validity.
  */
object ClusterNativeTransfer:
  val ProfileId = "conway-pv9-cluster-derived-native-comparison-v1"
  import NativeSpending.Error
  import NativeSpending.Error.*
  final class Receipt private[ClusterNativeTransfer] (
      val bound: ClusterIntervalTransfer.Bound,
      val admission: NativeSpending.Admission,
      val minimum: MinimumOutput.Receipt,
      val fee: BigInt,
      val spent: Set[TxIn],
      val created: Set[TxIn],
      val untouchedEntries: Int
  ):
    val profileId = ProfileId
    val transactionId = admission.interval.transactionId
    val credentialBound = true
    val fullLedgerValidated = false
    val referenceSnapshotAtomic = false

  def compare(
      context: ClusterTransfer.Context,
      parameters: MinimumOutput.Parameters,
      pre: Bytes,
      post: Bytes,
      original: Bytes,
      blocks: Vector[Bytes]
  ): Either[Error, Receipt] =
    def error(f: ClusterTransition.Failure): Error = f match
      case ClusterTransition.Failure.Unsupported(reason)    => UnsupportedProfile(reason)
      case ClusterTransition.Failure.DecodeRejected(reason) => DecodeRejected(reason)
      case ClusterTransition.Failure.Malformed(reason)      => Malformed(reason)
      case ClusterTransition.Failure.ResourceLimit(reason)  => ResourceLimit(reason)
      case ClusterTransition.Failure.Rejected(predicate)    => predicate
      case ClusterTransition.Failure.InternalFailure(kind) =>
        InternalFailure(kind)
      case ClusterTransition.Failure.StaleState(reason) =>
        StateMismatch(s"local stale transition: $reason")
    for
      bound <- ClusterIntervalTransfer
        .bind(context, original, blocks)
        .left
        .map(CaptureRejected.apply)
      _ <- Either.cond(bound.interval.satisfied, (), OutsideValidityInterval)
      env <- ClusterTransition.fromContext(context, parameters).left.map(error)
      before <- ClusterTransition
        .checkpoint(env, pre, context.feesBefore, context.preSlot, context.preHash)
        .left
        .map(error)
      applied <- ClusterTransition
        .applyTransaction(before, original, bound.interval.slot)
        .left
        .map(error)
      admission <- applied.candidate.nativeAdmission.toRight(
        UnsupportedProfile("at least one enterprise native-script input required")
      )
      _ <- ClusterTransition.compareReference(applied, post, context.feesAfter).left.map(error)
      tx = applied.candidate
    yield new Receipt(
      bound,
      admission,
      tx.minimum,
      tx.fee,
      tx.spent,
      tx.created,
      before.size - tx.spent.size
    )
