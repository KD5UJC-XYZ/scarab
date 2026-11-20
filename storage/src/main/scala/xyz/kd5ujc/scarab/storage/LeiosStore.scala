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

/** The status of an announced point: the `status` column of the reference's volatile partition,
  * without its two transitional states (`Storage/LeiosDB/Impl/SQLite/Schema.hs:20-23` at 2416493b),
  * and whether the reference's collection evicted the point from that partition. A point is
  * [[Volatile]] until a certifying block that becomes immutable promotes its endorser block; it is
  * then [[Certified]] (the reference's pinned row, awaiting its copy into the immutable partition)
  * while none of its transactions is stored, and [[Immutable]] (the reference's copied row) once
  * one is, as the reference's copier copies an endorser block once it has a closure row
  * (`sql_copy_has_closure`, `Storage/LeiosDB/Impl/SQLite/Queries.hs:131-133`). A collection that
  * takes an immutable point makes it [[Evicted]]: the reference keeps it in its immutable partition
  * only, and so do not its volatile reads, which never see it. The reference's marked status has no
  * counterpart: [[LeiosStore.garbageCollect]] marks and sweeps a hash in one write.
  */
enum EbStatus(val code: Byte) derives CanEqual:
  case Volatile extends EbStatus(0)
  case Certified extends EbStatus(1)
  case Immutable extends EbStatus(2)
  case Evicted extends EbStatus(3)

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
  /** Record an announced point and its announced size (`writeEbPoint`). A point already stored
    * changes nothing, an [[EbStatus.Evicted]] one too, where the reference's volatile partition
    * takes it again (an approved Deviation, #830, decided 2026-11-19T20:18:25Z). A novel point of a
    * hash whose closure is complete in the reference's volatile partition is complete on arrival,
    * and is returned; one of a hash the collection evicted is not, as the reference holds its body
    * in its immutable partition only then.
    */
  def writeEbPoint(point: EbPoint, announced: Word32)(using
      Raise[F, LeiosStoreError]
  ): F[Vector[EbPoint]]

  /** Store a body, and fill what its fills can from transactions stored for other endorser blocks
    * (`writeEbBody`, `sqlInsertEbBody`). The point must have been written. A body already stored
    * changes nothing and completes nothing, unless the collection evicted its hash: the body is
    * then written to the reference's volatile partition again, its closure there empty until its
    * transactions are written again. The store holds one closure, so it empties it: a hash evicted
    * again before its transactions are written again holds only those written again, where the
    * reference still holds them all in its immutable partition (an approved Deviation, #830,
    * decided 2026-11-19T20:56:30Z). Its evicted points become immutable again, so that a later
    * collection evicts them again and keeps the endorser block: until it does, the point scans and
    * a completion list them with the points announced since, where the reference lists those alone
    * (an approved Deviation, #830, decided 2026-11-19T21:51:57Z).
    */
  def writeEbBody(point: EbPoint, body: LeiosEb, fills: Vector[LocalFill])(using
      Raise[F, LeiosStoreError]
  ): F[BodyWritten]

  /** Store transactions of the point's endorser block, by offset (`writeTxs`, `sqlInsertTxs`). A
    * transaction is dropped if the body is not stored (or its hash is evicted and the body not
    * written again), its offset is not in the body or came earlier in the same write, its size is
    * not the declared one, or its bytes are empty or start with a zero byte. Returns every point of
    * the hash when this write completed its closure.
    */
  def writeTxs(point: EbPoint, txs: Vector[(Int, Bytes)])(using
      Raise[F, LeiosStoreError]
  ): F[Vector[EbPoint]]

  /** The body as stored, its encoding (`lookupEbBody`). */
  def lookupEbBody(hash: EbHash)(using Raise[F, LeiosStoreError]): F[Option[Bytes]]

  /** The transactions' hashes and bytes in body order, if the closure is complete
    * (`lookupEbClosure`).
    */
  def lookupEbClosure(hash: EbHash)(using
      Raise[F, LeiosStoreError]
  ): F[Option[Vector[(LeiosTxHash, Bytes)]]]

  /** The bytes of the given offsets that the body lists, ascending and once each, zeroes for a
    * transaction not stored yet; nothing if the body is not stored (`batchRetrieveTxs`).
    */
  def batchRetrieveTxs(hash: EbHash, offsets: Vector[Int])(using
      Raise[F, LeiosStoreError]
  ): F[Vector[(Int, Bytes)]]

  /** Every point of the reference's volatile partition, by slot (`testScanEbPoints`): not the
    * [[EbStatus.Evicted]] ones.
    */
  def scanEbPoints(using Raise[F, LeiosStoreError]): F[Vector[EbPoint]]

  /** The points at or after `slot` whose closure is complete, oldest first
    * (`scanCompleteEbClosuresNotOlderThanSlot`, `sqlScanBodiedPointsSince`,
    * `Impl/SQLite/Read.hs:170-182` at 2416493b): what the reference reads from its volatile
    * partition, so never an [[EbStatus.Evicted]] point, nor a point of a hash the collection
    * evicted until its body and closure are written again.
    */
  def scanCompleteEbClosuresNotOlderThanSlot(slot: Word64)(using
      Raise[F, LeiosStoreError]
  ): F[Vector[EbPoint]]

  /** Collect what is older than `slot` (`leiosDbGarbageCollect`, `sql_gc_mark` and `sweepEbBatch`):
    * unless a point of the same hash is [[EbStatus.Certified]] or at or after `slot`, a
    * [[EbStatus.Volatile]] point older than `slot` goes and an [[EbStatus.Immutable]] one becomes
    * [[EbStatus.Evicted]]; then the body and transactions of a hash that no point names.
    */
  def garbageCollect(slot: Word64)(using Raise[F, LeiosStoreError]): F[Unit]

  /** Promote the points' endorser blocks (`leiosDbPromoteToImmutable`): every volatile or certified
    * point of each hash becomes [[EbStatus.Certified]], or [[EbStatus.Immutable]] if a transaction
    * of it is in the reference's volatile partition.
    */
  def promoteToImmutable(points: Vector[EbPoint])(using Raise[F, LeiosStoreError]): F[Unit]
