// SPDX-License-Identifier: Apache-2.0
package lab.ledger.runtime

import lab.cbor.Bytes
import lab.ledger.RestrictedReplay as R

/** An effectful owner of one bounded research projection. No block, consensus or full-ledger claim.
  */
trait ReplayStore[F[_]]:
  def snapshot: F[ReplayStore.Snapshot]
  def commit(
      expected: ReplayStore.Version,
      transactions: Vector[Bytes]
  ): F[Either[ReplayStore.Rejection, ReplayStore.Snapshot]]
  def rollback(
      expected: ReplayStore.Version,
      transition: Bytes
  ): F[Either[ReplayStore.Rejection, ReplayStore.Snapshot]]

object ReplayStore:
  final case class Checkpoint(parameters: Bytes, originalUtxo: Bytes, attributionDigest: Bytes)

  /** Session fencing is deliberately stricter than persisted revision fencing: reopening
    * invalidates every token from the previous owner, even when no commit occurred.
    */
  final class Version private[runtime] (
      private[runtime] val session: String,
      val head: Bytes,
      val revision: BigInt
  ):
    private[runtime] def matches(other: Version): Boolean =
      other != null && session == other.session && head == other.head && revision == other.revision

  final class Snapshot private[runtime] (
      val state: R.State,
      val version: Version,
      val undo: Option[R.BatchDelta],
      val historyLength: Int
  )

  enum Rejection:
    case StaleVersion
    case Ledger(failure: R.Failure)
    case RollbackMismatch
    case ResourceLimit(detail: String)

  enum StorageFailure:
    case InvalidDirectory, Locked, Closed, RequiresReopen, Corrupt, UnsupportedFilesystem

  final class StorageException(val failure: StorageFailure, detail: String)
      extends RuntimeException(s"$failure: $detail")

  /** Lower limits may be selected by a caller; no caller can raise the hard research ceilings.
    * These bound encoded storage and cumulative replay/evidence work, not exact JVM heap usage.
    */
  final case class Limits(
      maxHistory: Int = 128,
      maxTransactions: Int = 256,
      maxRecoveryBytes: Long = 32L * 1048576,
      maxEvidenceBytes: Long = 64L * 1048576,
      maxStoredBytes: Long = 64L * 1048576,
      maxFiles: Int = 512
  ):
    private[runtime] def validate(): Unit =
      require(maxHistory >= 1 && maxHistory <= 128, "history limit must be 1..128")
      require(maxTransactions >= 1 && maxTransactions <= 256, "transaction limit must be 1..256")
      require(maxRecoveryBytes >= 1 && maxRecoveryBytes <= 32L * 1048576, "recovery byte limit")
      require(maxEvidenceBytes >= 1 && maxEvidenceBytes <= 64L * 1048576, "evidence byte limit")
      require(maxStoredBytes >= 1 && maxStoredBytes <= 64L * 1048576, "stored byte limit")
      require(maxFiles >= 1 && maxFiles <= 512, "file limit must be 1..512")
