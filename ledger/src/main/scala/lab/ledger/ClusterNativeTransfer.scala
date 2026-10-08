// SPDX-License-Identifier: Apache-2.0
package lab.ledger

import lab.cbor.Bytes

/** Captured, restricted ADA native-script spending comparison, not consensus/full ledger validity.
  */
object ClusterNativeTransfer:
  val ProfileId = "conway-pv9-cluster-ada-native-transition-v1"
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
    for
      bound <- ClusterIntervalTransfer
        .bind(context, original, blocks)
        .left
        .map(CaptureRejected.apply)
      _ <- Either.cond(bound.interval.satisfied, (), OutsideValidityInterval)
      admission <- NativeSpending.check(original, pre)
      tx = admission.projection
      minimum <- MinimumOutput
        .check(parameters, admission.semanticEnvelope)
        .left
        .map(UnsupportedProfile.apply)
      _ <- Either.cond(minimum.satisfied, (), MinimumOutputFailed)
      produced = tx.outputs.map(_.value.lovelace).sum + tx.fee
      _ <- Either.cond(
        admission.consumedCoin == produced,
        (),
        ValueNotConserved(admission.consumedCoin, produced)
      )
      requiredFee = admission.memoSize * context.parameters.feePerByte + context.parameters.feeFixed
      _ <- Either.cond(tx.fee >= requiredFee, (), FeeTooSmall(tx.fee, requiredFee))
      _ <- Either.cond(
        admission.memoSize <= context.parameters.maxTxSize,
        (),
        TransactionTooLarge(admission.memoSize, context.parameters.maxTxSize)
      )
      before <- NativeSpending.snapshot(pre)
      after <- NativeSpending.snapshot(post)
      created <- tx.outputs.zipWithIndex.foldLeft[Either[Error, Map[TxIn, Coverage.Output]]](
        Right(Map.empty)
      ) { case (acc, (out, index)) =>
        for
          previous <- acc
          input <- TxIn
            .create(admission.interval.transactionId, BigInt(index))
            .left
            .map(e => Malformed(e.toString))
          _ <- Either.cond(!before.contains(input), (), StateMismatch("created output collision"))
        yield previous.updated(input, out)
      }
      _ <- Either.cond(
        after.keySet == (before.keySet -- tx.inputs) ++ created.keySet,
        (),
        StateMismatch("UTxO reference delta differs")
      )
      _ <- Either.cond(
        (before.keySet -- tx.inputs).forall(ref => before(ref).original == after(ref).original),
        (),
        StateMismatch("untouched output bytes changed")
      )
      _ <- created.toVector.foldLeft[Either[Error, Unit]](Right(())) {
        case (acc, (ref, expected)) =>
          for
            _ <- acc
            observed <- NativeSpending.output(after(ref), false).left.map(StateMismatch.apply)
            _ <- Either.cond(
              observed.address == expected.address && observed.coin == expected.value.lovelace,
              (),
              StateMismatch("created output address/value differs")
            )
          yield ()
      }
      _ <- Either.cond(
        context.feesAfter - context.feesBefore == tx.fee,
        (),
        StateMismatch("fee-pot delta differs")
      )
    yield new Receipt(
      bound,
      admission,
      minimum,
      tx.fee,
      tx.inputs,
      created.keySet,
      before.size - tx.inputs.size
    )
