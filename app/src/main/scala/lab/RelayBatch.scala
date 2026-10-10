// SPDX-License-Identifier: Apache-2.0
package lab

import cats.syntax.all.*
import lab.cbor.Bytes
import lab.network.{RelayLimits, RelayOffer, TxSubmission2}
import lab.submission.SignedTransaction

/** Pure stable first-fit selection, retaining exact original bytes with their offers. */
private[lab] object RelayBatch:
  enum Failure:
    case InvalidLimits, InvalidEligibleDomain, DuplicateIdentity
    case InvalidOriginalSize(detail: String)

  final class Batch private[RelayBatch] (
      val offers: Vector[RelayOffer],
      val originals: Map[Bytes, Bytes],
      val originalBytes: Int
  )

  private val empty = new Batch(Vector.empty, Map.empty, 0)

  def select(eligible: Vector[SignedTransaction], limits: RelayLimits): Either[Failure, Batch] =
    if limits == null || !limits.valid then Left(Failure.InvalidLimits)
    else if eligible == null || eligible.size > 64 || eligible.exists(_ == null) then
      Left(Failure.InvalidEligibleDomain)
    else if eligible.map(_.transactionId).distinct.size != eligible.size then
      Left(Failure.DuplicateIdentity)
    else
      eligible.foldLeft[Either[Failure, Batch]](Right(empty)) { (acc, transaction) =>
        acc.flatMap { batch =>
          if batch.offers.size >= limits.maxTransactions || transaction.byteSize > limits.maxOriginalBytes - batch.originalBytes
          then Right(batch)
          else
            TxSubmission2
              .advertisedSize(transaction.byteSize)
              .leftMap(Failure.InvalidOriginalSize.apply)
              .map { size =>
                new Batch(
                  batch.offers :+ RelayOffer(transaction.transactionId, size),
                  batch.originals.updated(transaction.transactionId, transaction.original),
                  batch.originalBytes + transaction.byteSize
                )
              }
        }
      }
