// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage

import cats.mtl.Raise
import xyz.kd5ujc.scarab.codec.{Bytes, DomainError}
import xyz.kd5ujc.scarab.protocol.{EbHash, EbPoint, LeiosTxHash}
import xyz.kd5ujc.scarab.protocol.LeiosWord.{Word32, Word64}

/** Where a transaction's bytes are held: an endorser block that stores it, and its offset there
  * (`TxLocation`, ouroboros-consensus 2416493b `Ouroboros/Consensus/Leios/Types.hs:199-204`).
  */
final case class TxLocation(hash: EbHash, offset: Int)

/** Fill the transaction at `offset` of a body from the bytes another endorser block holds
  * (`LocalFill`, `Storage/LeiosDB/API.hs:173-175` at 2416493b).
  */
final case class LocalFill(offset: Int, source: TxLocation)

/** What a body write did: the points it completed and the offsets its fills filled. */
final case class BodyWritten(completed: Vector[EbPoint], filled: Vector[Int])

/** The status of a row of the volatile partition: the `status` column of the reference's
  * `leios.vol.db` (`Storage/LeiosDB/Impl/SQLite/Schema.hs:20-23` at 2416493b). A row is
  * [[Volatile]] when announced; [[Certified]] once a certifying block that becomes immutable
  * promotes its endorser block (the reference's pinned row, awaiting the copier); [[Copied]] once
  * the copier copied the endorser block into the immutable partition; and [[Marked]] once a
  * collection marked it for the sweeper, which removes the hash from the volatile partition when
  * all its rows are marked. The store's own collection marks and sweeps in one write, so a row is
  * marked only as read from a reference's database whose sweep had not run.
  */
enum EbStatus(val code: Byte) derives CanEqual:
  case Volatile extends EbStatus(0)
  case Certified extends EbStatus(1)
  case Copied extends EbStatus(2)
  case Marked extends EbStatus(3)

object EbStatus:
  def fromCode(code: Byte): Option[EbStatus] = values.find(_.code == code)

/** A record of the Leios store that does not decode. */
enum LeiosRecord derives CanEqual:
  case Format, PointKey, PointValue, TxKey

/** Why an operation on the Leios store failed. */
enum LeiosStoreError extends DomainError:
  /** An endorser-block hash that is not 32 bytes: the store's keys hold `PackedBytes 32` hashes. */
  case HashLength(found: Int)

  /** A body written before its point: the point must be written first, as the reference's in-memory
    * store requires (`writeEbBody: point not registered`, `Storage/LeiosDB/Impl/InMemory.hs`,
    * `imInsertEbBody`).
    */
  case PointNotWritten(point: EbPoint)

  /** The directory holds a RocksDB database whose column families are not the store's. */
  case ForeignDatabase(families: Vector[Bytes])

  /** The store's format record is missing. */
  case FormatMissing

  /** The store records another format or version (`found`, as stored): it is not opened, and
    * nothing is migrated.
    */
  case UnknownFormat(found: Bytes)

  /** A stored record that does not decode (`key`, as stored). */
  case Corrupt(record: LeiosRecord, key: Bytes)

  /** A stored body that does not decode. */
  case CorruptBody(hash: EbHash, cause: LeiosEb.Error)

  /** An imported body for `hash` that differs from the one stored. */
  case BodyConflict(hash: EbHash)

  /** A transaction declared larger than the JVM can hold in one array. */
  case DeclaredSizeTooLarge(hash: EbHash, offset: Int, size: Long)

  /** The store was released. */
  case Closed

  /** RocksDB or the operating system failed. */
  case Io(cause: Throwable)

  def message: String = this match
    case HashLength(n) => s"an endorser-block hash of $n bytes, not 32"
    case PointNotWritten(p) =>
      s"the body of ${p.hash.bytes.hex} at slot ${p.slot.toBigInt} before its point"
    case ForeignDatabase(found) =>
      s"not a Leios store: its column families are ${found.map(_.hex).mkString(", ")}"
    case FormatMissing => "the Leios store has no format record"
    case UnknownFormat(found) =>
      val expected = RocksLeiosStore.FormatRecord.hex
      s"the Leios store records format ${found.hex}, this build reads $expected"
    case Corrupt(record, key) => s"a Leios store record ($record) does not decode at key ${key.hex}"
    case CorruptBody(hash, e) => s"the stored body of ${hash.bytes.hex} does not decode: $e"
    case BodyConflict(hash) =>
      s"the body imported for ${hash.bytes.hex} differs from the one stored"
    case DeclaredSizeTooLarge(hash, o, size) =>
      s"transaction $o of ${hash.bytes.hex} is declared at $size bytes, more than an array holds"
    case Closed => "the Leios store is closed"
    case Io(e)  => s"the Leios store failed: ${e.getMessage}"

/** The store of endorser blocks: announced points, bodies and their transactions, mirroring the
  * reference's `LeiosDbReader` and `LeiosDbWriter` (ouroboros-consensus 2416493b
  * `Storage/LeiosDB/API.hs`) with its SQLite backend's behaviour (`Impl/SQLite/Insert.hs`,
  * `Read.hs`, `Maintenance.hs`). Writes are serialised, as the reference's single write queue
  * serialises them, and each returns the points whose closure it completed.
  */
trait LeiosStore[F[_]]:
  /** Record an announced point and its announced size (`writeEbPoint`) in the volatile partition. A
    * point the volatile partition holds changes nothing. A novel point of a hash whose closure is
    * complete in the volatile partition is complete on arrival, and is returned.
    */
  def writeEbPoint(point: EbPoint, announced: Word32)(using
      Raise[F, LeiosStoreError]
  ): F[Vector[EbPoint]]

  /** Store a body in the volatile partition, and fill what its fills can from transactions the
    * volatile partition holds for other endorser blocks (`writeEbBody`, `sqlInsertEbBody`). The
    * point must be in the volatile partition. A body the volatile partition holds changes nothing
    * and completes nothing. Fills allocate the closure, whether they fill anything or not.
    */
  def writeEbBody(point: EbPoint, body: LeiosEb, fills: Vector[LocalFill])(using
      Raise[F, LeiosStoreError]
  ): F[BodyWritten]

  /** Store transactions of the point's endorser block in its volatile closure, by offset
    * (`writeTxs`, `sqlInsertTxs`), allocating the closure if none is. A transaction is dropped if
    * the volatile partition holds no body, its offset is not in the body or came earlier in the
    * same write, its size is not the declared one, or its bytes are empty or start with a zero
    * byte. Returns every point of the hash when this write completed its closure.
    */
  def writeTxs(point: EbPoint, txs: Vector[(Int, Bytes)])(using
      Raise[F, LeiosStoreError]
  ): F[Vector[EbPoint]]

  /** The body as stored, its encoding, from the volatile partition or else the immutable one
    * (`lookupEbBody`).
    */
  def lookupEbBody(hash: EbHash)(using Raise[F, LeiosStoreError]): F[Option[Bytes]]

  /** The transactions' hashes and bytes in body order, if the closure is complete
    * (`lookupEbClosure`): nothing while the volatile partition holds the body and not its whole
    * closure; otherwise the closure of the partition that holds the body.
    */
  def lookupEbClosure(hash: EbHash)(using
      Raise[F, LeiosStoreError]
  ): F[Option[Vector[(LeiosTxHash, Bytes)]]]

  /** The bytes of the given offsets that the body lists, ascending and once each, zeroes for a
    * transaction not written; nothing if the body is not stored (`batchRetrieveTxs`). They are read
    * from the partition that holds the body, the volatile one first.
    */
  def batchRetrieveTxs(hash: EbHash, offsets: Vector[Int])(using
      Raise[F, LeiosStoreError]
  ): F[Vector[(Int, Bytes)]]

  /** Every point of the volatile partition, by slot (`testScanEbPoints`). */
  def scanEbPoints(using Raise[F, LeiosStoreError]): F[Vector[EbPoint]]

  /** The points at or after `slot` whose closure is complete, oldest first
    * (`scanCompleteEbClosuresNotOlderThanSlot`, `sqlScanBodiedPointsSince`,
    * `Impl/SQLite/Read.hs:170-182` at 2416493b): the points of the volatile partition whose body
    * and whole closure it holds.
    */
  def scanCompleteEbClosuresNotOlderThanSlot(slot: Word64)(using
      Raise[F, LeiosStoreError]
  ): F[Vector[EbPoint]]

  /** Collect what is older than `slot` (`leiosDbGarbageCollect`, `sql_gc_mark` and `sweepEbBatch`):
    * the hashes all of whose rows are [[EbStatus.Marked]] go from the volatile partition, and so do
    * those with a row older than `slot` unless one of their rows is [[EbStatus.Certified]] or at or
    * after `slot`: their rows, body and closure. The immutable partition keeps what was copied.
    */
  def garbageCollect(slot: Word64)(using Raise[F, LeiosStoreError]): F[Unit]

  /** Promote the points' endorser blocks (`leiosDbPromoteToImmutable`): every [[EbStatus.Volatile]]
    * or [[EbStatus.Marked]] row of each hash becomes [[EbStatus.Certified]], and a hash whose
    * volatile partition holds its closure is copied into the immutable partition: its newest row,
    * its body and its closure, each if not there yet, its certified rows becoming
    * [[EbStatus.Copied]]. A later write of the volatile closure of a hash with a certified row
    * copies it the same way.
    */
  def promoteToImmutable(points: Vector[EbPoint])(using Raise[F, LeiosStoreError]): F[Unit]
