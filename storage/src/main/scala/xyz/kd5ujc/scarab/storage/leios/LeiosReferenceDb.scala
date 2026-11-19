// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage.leios

import cats.Functor
import cats.effect.{Async, Resource}
import cats.mtl.Raise
import cats.mtl.syntax.all.*
import cats.syntax.all.*
import fs2.Stream
import fs2.io.file.{Files, Path}
import java.sql.{Connection, PreparedStatement, ResultSet}
import org.sqlite.SQLiteConfig
import scala.collection.immutable.SortedMap
import xyz.kd5ujc.scarab.codec.{Bytes, DomainError}
import xyz.kd5ujc.scarab.protocol.{EbHash, EbPoint}
import xyz.kd5ujc.scarab.protocol.LeiosWord.{Word32, Word64}
import xyz.kd5ujc.scarab.storage.{EbStatus, LeiosEb, LeiosStoreError, RocksLeiosStore, StoredEb}

/** The reference node's Leios database: the SQLite files `leios.vol.db` and `leios.imm.db` in its
  * database directory (ouroboros-consensus 2416493b `Cardano/Tools/DBAnalyser/Run.hs:294-295` of
  * `ouroboros-consensus-cardano`), read into the [[RocksLeiosStore]] and written from it, so that
  * the store can be checked against the reference. Offline only: the node itself never opens
  * SQLite.
  *
  * The files have the reference's schema (`Storage/LeiosDB/Impl/SQLite/Schema.hs`): `ebs` holds the
  * points, `ebBodies` each body with its `txBounds` (`encodeTxBounds`,
  * `Impl/SQLite/Read.hs:264-271`), `ebClosures` each closure, its transactions' bytes concatenated
  * with zeroes for those not written (`Impl/SQLite/Insert.hs:337-343`). The volatile file's `ebs`
  * has the `status` column; the immutable file holds the endorser blocks the copier copied there,
  * without it (`Impl/SQLite/Maintenance.hs:112-168`).
  *
  * Import reads the immutable file, then the volatile one, without writing either or beside them
  * while neither has a write-ahead log with frames (see `Access`). A point of the immutable file is
  * [[EbStatus.Immutable]] if the volatile file holds its body, and [[EbStatus.Evicted]] if the
  * reference's collection took that; a point of the volatile file has its status there, the status
  * the reference's collection marked (3) read as [[EbStatus.Volatile]], since the next collection
  * takes the point again; a point in both keeps the later status. A closure's written transactions
  * are stored, the volatile file's where it holds the body, and completion is what the reference's
  * `scanMissing` reads from the bytes (`Impl/SQLite/Completion.hs:53-72`).
  *
  * Export writes two new files with the reference's pragmas and schema (`initialiseLeiosDbFiles`,
  * `Impl/SQLite/Connection.hs:62-73`), as the reference would hold the store, each file with the
  * bodies and closures of the points it holds. The immutable file has the newest immutable or
  * evicted point of each hash, the one point the reference's copier copies (`sql_copy_insert_eb`,
  * `Impl/SQLite/Queries.hs:142-148`). The volatile file has every point that is not evicted, the
  * immutable ones with the copier's status 2, and the body and closure of a hash none of whose
  * points is evicted. An import gives back every point, but of a hash's evicted points the newest
  * only, as the reference keeps them.
  */
object LeiosReferenceDb:
  /** The two files of the reference's Leios database. */
  enum Partition(val file: String) derives CanEqual:
    case Volatile extends Partition("leios.vol.db")
    case Immutable extends Partition("leios.imm.db")

  /** Why the reference's database was not read or written. */
  enum Error extends DomainError:
    case Store(cause: LeiosStoreError)

    /** SQLite failed on the partition's file. */
    case Sql(partition: Partition, cause: Throwable)

    /** One of the two files exists and the other does not. */
    case PartitionMissing(partition: Partition)

    /** The export's target file exists already. */
    case TargetExists(partition: Partition)

    /** A hash of `found` bytes where the schema holds 32. */
    case BadHash(partition: Partition, found: Int)

    /** A slot outside a 64-bit word. */
    case BadSlot(partition: Partition, hash: EbHash, slot: BigInt)

    /** An announced size outside a 32-bit word. */
    case BadSize(partition: Partition, hash: EbHash, size: Long)

    /** A `status` the reference does not write. */
    case BadStatus(hash: EbHash, status: Long)

    case BadBody(partition: Partition, hash: EbHash, cause: LeiosEb.Error)

    /** A body's `txBounds` that are not the bounds of its transactions' declared sizes. */
    case BoundsDisagree(partition: Partition, hash: EbHash)

    /** A closure whose size is not the sum of the declared sizes. */
    case ClosureSize(partition: Partition, hash: EbHash, expected: Long, found: Int)

    /** A closure larger than its bounds' 32-bit words or than a JVM array. */
    case ClosureTooLarge(hash: EbHash, size: Long)

    def message: String = this match
      case Store(e)  => e.message
      case Sql(p, e) => s"${p.file}: ${e.getMessage}"
      case PartitionMissing(p) =>
        s"${p.file} is missing beside the other file of the Leios database"
      case TargetExists(p) => s"${p.file} exists already"
      case BadHash(p, n)   => s"${p.file}: an endorser-block hash of $n bytes, not 32"
      case BadSlot(p, hash, slot) =>
        s"${p.file}: ${hash.bytes.hex} at slot $slot, outside a 64-bit word"
      case BadSize(p, hash, size) =>
        s"${p.file}: ${hash.bytes.hex} announced at $size bytes, outside a 32-bit word"
      case BadStatus(hash, n)  => s"leios.vol.db: ${hash.bytes.hex} has status $n"
      case BadBody(p, hash, e) => s"${p.file}: the body of ${hash.bytes.hex} does not decode: $e"
      case BoundsDisagree(p, hash) =>
        s"${p.file}: the txBounds of ${hash.bytes.hex} are not its transactions' declared sizes"
      case ClosureSize(p, hash, expected, found) =>
        s"${p.file}: the closure of ${hash.bytes.hex} holds $found bytes, its bounds $expected"
      case ClosureTooLarge(hash, size) =>
        s"the closure of ${hash.bytes.hex} is $size bytes, too large"

  /** What an import read or an export wrote. */
  final case class Counts(points: Long, bodies: Long, transactions: Long):
    def +(other: Counts): Counts =
      Counts(points + other.points, bodies + other.bodies, transactions + other.transactions)

  object Counts:
    val zero: Counts = Counts(0, 0, 0)

  /** The reference's pragmas (`connectionPragmas` and `creationPragmas`,
    * `Impl/SQLite/Connection.hs:84-130` at 2416493b), in the order it sets them.
    */
  val Pragmas: Vector[String] = Vector(
    "pragma busy_timeout = 1000;",
    "pragma synchronous = normal;",
    "pragma mmap_size = 268435500;",
    "pragma wal_autocheckpoint = 1000;",
    "pragma temp_store = memory;",
    "pragma journal_size_limit = 67108864;",
    "pragma page_size = 4096;",
    "pragma journal_mode = WAL;"
  )

  private def ebsTable(extra: Vector[String]): Vector[String] =
    Vector(
      "CREATE TABLE IF NOT EXISTS ebs (",
      "  ebSlot INTEGER NOT NULL,",
      "  ebHashBytes BLOB NOT NULL,",
      "  ebBytesSize INTEGER NOT NULL,"
    ) ++ extra ++ Vector(
      "  PRIMARY KEY (ebSlot, ebHashBytes)",
      ");",
      "CREATE INDEX IF NOT EXISTS idx_ebs_ebHashBytes ON ebs(ebHashBytes);"
    )

  private val sharedTables: Vector[String] = Vector(
    "CREATE TABLE IF NOT EXISTS ebBodies (",
    "  ebHashBytes BLOB NOT NULL PRIMARY KEY,",
    "  txBounds BLOB NOT NULL,",
    "  body BLOB NOT NULL",
    ");",
    "CREATE TABLE IF NOT EXISTS ebClosures (",
    "  ebHashBytes BLOB NOT NULL PRIMARY KEY,",
    "  txs BLOB NOT NULL",
    ");"
  )

  /** `sql_schema_vol` and `sql_schema_imm` (`Impl/SQLite/Schema.hs:16-52` at 2416493b), line for
    * line.
    */
  def schema(partition: Partition): String = partition match
    case Partition.Volatile =>
      (ebsTable(Vector("  status INTEGER NOT NULL DEFAULT 0,")) ++ sharedTables)
        .mkString("", "\n", "\n") +
        Vector(
          "CREATE INDEX IF NOT EXISTS idx_ebs_sweepable ON ebs(ebSlot) WHERE status IN (0, 2);",
          "CREATE INDEX IF NOT EXISTS idx_ebs_markedForGc ON ebs(ebHashBytes) WHERE status = 3;",
          "CREATE INDEX IF NOT EXISTS idx_ebs_pinned ON ebs(ebSlot) WHERE status = 1;"
        ).mkString("", "\n", "\n")
    case Partition.Immutable => (ebsTable(Vector.empty) ++ sharedTables).mkString("", "\n", "\n")

  /** The schema's statements, one at a time: the driver runs one statement per call. */
  private def statements(partition: Partition): Vector[String] =
    schema(partition).split(';').toVector.map(_.trim).filter(_.nonEmpty)

  /** Whether `dir` holds the reference's Leios database: both its files, or neither. */
  def present[F[_]: Async](dir: Path)(using Raise[F, Error]): F[Boolean] =
    Partition.values.toVector
      .traverse(p => Files.forAsync[F].exists(dir / p.file).map(p -> _))
      .flatMap { found =>
        found.collectFirst { case (p, false) => p } match
          case Some(missing) if found.exists(_._2) =>
            Error.PartitionMissing(missing).raise[F, Boolean]
          case Some(_) => Async[F].pure(false)
          case None    => Async[F].pure(true)
      }

  // --- import ---

  /** Read the reference's database in `dir` into `store`. */
  def importInto[F[_]: Async](dir: Path, store: RocksLeiosStore[F])(using
      outer: Raise[F, Error]
  ): F[Counts] =
    given Raise[F, LeiosStoreError] = storeRaise[F]
    val files = Files.forAsync[F]
    def opened[A](partition: Partition)(use: Connection => F[A]): F[A] =
      val wal = dir / (partition.file + "-wal")
      files
        .exists(wal)
        .ifM(files.size(wal).map(_ > 0), Async[F].pure(false))
        .map(if _ then Access.ReadOnly else Access.Immutable)
        .flatMap(access => connection[F](dir / partition.file, partition, access).use(use))
    // the hashes whose body the volatile file holds: the others the collection evicted
    opened(Partition.Volatile)(c =>
      rows(c, Partition.Volatile, "SELECT ebHashBytes FROM ebBodies")(r =>
        Bytes.wrap(blob(r, 1))
      ).compile
        .to(Set)
    ).flatMap { held =>
      Vector(Partition.Immutable, Partition.Volatile).foldLeftM(Counts.zero) {
        (counts, partition) =>
          opened(partition)(importPartition(_, partition, held, store)).map(counts + _)
      }
    }

  private final case class PointRow(slot: Long, hash: Array[Byte], size: Long, status: Long)
  private final case class BodyRow(
      hash: Array[Byte],
      bounds: Array[Byte],
      body: Array[Byte],
      closure: Option[Array[Byte]]
  )

  private def importPartition[F[_]: Async](
      c: Connection,
      partition: Partition,
      held: Set[Bytes],
      store: RocksLeiosStore[F]
  )(using Raise[F, Error], Raise[F, LeiosStoreError]): F[Counts] =
    // a hash of the immutable file the volatile file holds no body of was evicted from it
    val immutableOnly = (hash: Array[Byte]) =>
      partition == Partition.Immutable && !held.contains(Bytes.wrap(hash))
    val pointQuery = partition match
      case Partition.Volatile  => "SELECT ebSlot, ebHashBytes, ebBytesSize, status FROM ebs"
      case Partition.Immutable => "SELECT ebSlot, ebHashBytes, ebBytesSize, 2 FROM ebs"
    val bodyQuery =
      "SELECT b.ebHashBytes, b.txBounds, b.body, c.txs\n" +
        "FROM ebBodies b LEFT JOIN ebClosures c ON c.ebHashBytes = b.ebHashBytes"
    val points = rows(c, partition, pointQuery)(r =>
      PointRow(r.getLong(1), blob(r, 2), r.getLong(3), r.getLong(4))
    ).evalMap(row => checked(point(partition, row, evicted = immutableOnly(row.hash))))
      .chunkN(4096)
      .evalMap(chunk => store.importPoints(chunk.toVector).as(chunk.size.toLong))
      .compile
      .fold(0L)(_ + _)
    val bodies = rows(c, partition, bodyQuery)(r =>
      BodyRow(blob(r, 1), blob(r, 2), blob(r, 3), Option(r.getBytes(4)))
    ).filter(row => partition == Partition.Volatile || immutableOnly(row.hash))
      .evalMap { row =>
        checked(closure(partition, row)).flatMap { (hash, raw, eb, txs) =>
          store.importBody(hash, raw, eb, txs).as(txs.size.toLong)
        }
      }
      .compile
      .fold((0L, 0L))((acc, txs) => (acc._1 + 1, acc._2 + txs))
    (points, bodies).mapN((p, b) => Counts(p, b._1, b._2))

  private def checked[F[_]: Async, A](result: Either[Error, A])(using Raise[F, Error]): F[A] =
    result.fold(_.raise[F, A], Async[F].pure)

  /** A blob column; a `NULL` in a `NOT NULL` column reads as no bytes, which the checks refuse. */
  private def blob(r: ResultSet, column: Int): Array[Byte] =
    Option(r.getBytes(column)).getOrElse(Array.emptyByteArray)

  private def point(
      partition: Partition,
      row: PointRow,
      evicted: Boolean
  ): Either[Error, (EbPoint, Word32, EbStatus)] =
    // the reference binds the slot's 64 bits as SQLite's signed integer
    // (`Insert.hs:86` at 2416493b)
    val unsigned = BigInt(row.slot) & ((BigInt(1) << 64) - 1)
    for
      hash <- hashOf(partition, row.hash)
      slot <- Word64.from(unsigned).leftMap(_ => Error.BadSlot(partition, hash, unsigned))
      size <- Word32.from(BigInt(row.size)).leftMap(_ => Error.BadSize(partition, hash, row.size))
      status <- row.status match
        case _ if evicted => Right(EbStatus.Evicted)
        case 0 | 3        => Right(EbStatus.Volatile)
        case 1            => Right(EbStatus.Certified)
        case 2            => Right(EbStatus.Immutable)
        case other        => Left(Error.BadStatus(hash, other))
    yield (EbPoint(slot, hash), size, status)

  private def hashOf(partition: Partition, raw: Array[Byte]): Either[Error, EbHash] =
    Either.cond(
      raw.length == LeiosEb.HashBytes,
      EbHash.fromBytes(Bytes.wrap(raw)),
      Error.BadHash(partition, raw.length)
    )

  /** A body and the written transactions of its closure, checked against its bounds. */
  private def closure(
      partition: Partition,
      row: BodyRow
  ): Either[Error, (EbHash, Bytes, LeiosEb, SortedMap[Int, Bytes])] =
    for
      hash <- hashOf(partition, row.hash)
      raw = Bytes.wrap(row.body)
      eb <- LeiosEb.decode(raw).leftMap(Error.BadBody(partition, hash, _))
      bounds <- eb.txBounds.toRight(
        Error.ClosureTooLarge(hash, eb.bounds.lastOption.getOrElse(0L))
      )
      _ <- Either.cond(bounds == Bytes.wrap(row.bounds), (), Error.BoundsDisagree(partition, hash))
      total = eb.bounds.lastOption.getOrElse(0L)
      txs <- row.closure.fold(Right(SortedMap.empty[Int, Bytes])) { blob =>
        Either.cond(
          blob.length.toLong == total,
          SortedMap.from(
            eb.bounds
              .zip(eb.bounds.drop(1))
              .zipWithIndex
              .map { case ((from, until), o) =>
                o -> Bytes.wrap(blob.slice(from.toInt, until.toInt))
              }
              .filter((_, tx) => LeiosEb.written(tx))
          ),
          Error.ClosureSize(partition, hash, total, blob.length)
        )
      }
    yield (hash, raw, eb, txs)

  // --- export ---

  /** Write `store` to new files of the reference's database in `dir`. */
  def exportFrom[F[_]: Async](store: RocksLeiosStore[F], dir: Path)(using
      Raise[F, Error]
  ): F[Counts] =
    val F = Async[F]
    given Raise[F, LeiosStoreError] = storeRaise[F]
    val files = Files.forAsync[F]
    val targets = Partition.values.toVector
    for
      _ <- targets.traverse_(p =>
        Vector("", "-wal", "-shm").traverse_(suffix =>
          files.exists(dir / (p.file + suffix)).ifM(Error.TargetExists(p).raise[F, Unit], F.unit)
        )
      )
      _ <- files.createDirectories(dir)
      counts <- (
        connection[F](dir / Partition.Volatile.file, Partition.Volatile, Access.Write),
        connection[F](dir / Partition.Immutable.file, Partition.Immutable, Access.Write)
      ).tupled.use { (vol, imm) =>
        for
          _ <- initialise(vol, Partition.Volatile) *> initialise(imm, Partition.Immutable)
          counts <- (Writer.of(vol, Partition.Volatile), Writer.of(imm, Partition.Immutable)).tupled
            .use { (volW, immW) =>
              // the store's hashes and what it holds of each, from one snapshot of it
              store.exporting { (hashes, stored) =>
                Stream
                  .emits(hashes)
                  .evalMap(stored)
                  .evalMap(eb => write(eb, volW, immW))
                  .chunkN(256)
                  .evalMap(chunk => commit(vol, imm).as(chunk.foldLeft(Counts.zero)(_ + _)))
                  .compile
                  .fold(Counts.zero)(_ + _)
              }
            }
          _ <- commit(vol, imm)
        yield counts
      }
    yield counts

  /** The statements an export inserts with, prepared on one partition. */
  private final class Writer(
      val partition: Partition,
      val point: PreparedStatement,
      val body: PreparedStatement,
      val closure: PreparedStatement
  )

  private object Writer:
    def of[F[_]: Async](c: Connection, partition: Partition)(using
        Raise[F, Error]
    ): Resource[F, Writer] =
      def prepared(sql: String) =
        Resource.make(blocking(partition)(c.prepareStatement(sql)))(s =>
          Async[F].blocking(s.close())
        )
      val points = partition match
        case Partition.Volatile =>
          "INSERT INTO ebs (ebSlot, ebHashBytes, ebBytesSize, status) VALUES (?1, ?2, ?3, ?4)"
        case Partition.Immutable =>
          "INSERT INTO ebs (ebSlot, ebHashBytes, ebBytesSize) VALUES (?1, ?2, ?3)"
      (
        prepared(points),
        prepared("INSERT INTO ebBodies (ebHashBytes, txBounds, body) VALUES (?1, ?2, ?3)"),
        prepared("INSERT INTO ebClosures (ebHashBytes, txs) VALUES (?1, ?2)")
      ).mapN(new Writer(partition, _, _, _))

  private def write[F[_]: Async](eb: StoredEb, vol: Writer, imm: Writer)(using
      Raise[F, Error]
  ): F[Counts] =
    val gone = eb.points.exists(_._3 == EbStatus.Evicted)
    val newest = eb.points
      .filter(p => p._3 == EbStatus.Immutable || p._3 == EbStatus.Evicted)
      .maxByOption(_._1.toBigInt)
    val immutable = newest.toVector
    val volatile = eb.points.filter(_._3 != EbStatus.Evicted)
    val hash = eb.hash.bytes.toArray
    val withBody: F[Option[(LeiosEb, Bytes, Bytes)]] = eb.body.traverse { raw =>
      LeiosEb.decode(raw) match
        case Left(e) =>
          Error.Store(LeiosStoreError.CorruptBody(eb.hash, e)).raise[F, (LeiosEb, Bytes, Bytes)]
        case Right(decoded) =>
          val total = decoded.bounds.lastOption.getOrElse(0L)
          decoded.txBounds.filter(_ => total <= Int.MaxValue) match
            case None => Error.ClosureTooLarge(eb.hash, total).raise[F, (LeiosEb, Bytes, Bytes)]
            case Some(bounds) => Async[F].pure((decoded, raw, bounds))
    }
    def into(
        w: Writer,
        points: Vector[(Word64, Long, EbStatus)],
        body: Option[(LeiosEb, Bytes, Bytes)]
    ) =
      blocking(w.partition) {
        points.foreach { (slot, size, status) =>
          w.point.setLong(1, slot.toBigInt.longValue)
          w.point.setBytes(2, hash)
          w.point.setLong(3, size)
          if w.partition == Partition.Volatile then w.point.setLong(4, status.code.toLong)
          w.point.executeUpdate()
        }
        body.foreach { (decoded, raw, bounds) =>
          w.body.setBytes(1, hash)
          w.body.setBytes(2, bounds.toArray)
          w.body.setBytes(3, raw.toArray)
          w.body.executeUpdate()
          if eb.txs.nonEmpty then
            w.closure.setBytes(1, hash)
            w.closure.setBytes(2, closureOf(decoded, eb.txs))
            w.closure.executeUpdate()
        }
      }
    withBody.flatMap { body =>
      val volBody = body.filter(_ => !gone && (volatile.nonEmpty || immutable.isEmpty))
      val immBody = body.filter(_ => immutable.nonEmpty)
      into(vol, volatile, volBody) *> into(imm, immutable, immBody).as(
        Counts(
          (volatile.size + immutable.size).toLong,
          volBody.fold(0L)(_ => 1L) + immBody.fold(0L)(_ => 1L),
          eb.txs.size.toLong
        )
      )
    }

  /** A closure as the reference holds it: every transaction at its bound, zeroes where none is
    * stored (`allocateClosure`, `Impl/SQLite/Insert.hs:337-343` at 2416493b).
    */
  private def closureOf(eb: LeiosEb, txs: SortedMap[Int, Bytes]): Array[Byte] =
    val bounds = eb.bounds
    val blob = new Array[Byte](bounds.lastOption.getOrElse(0L).toInt)
    txs.foreach((o, tx) => bounds.lift(o).foreach(at => tx.copyTo(blob, at.toInt)))
    blob

  // --- SQLite ---

  private def storeRaise[F[_]](using outer: Raise[F, Error]): Raise[F, LeiosStoreError] =
    new Raise[F, LeiosStoreError]:
      def functor: Functor[F] = outer.functor
      def raise[E2 <: LeiosStoreError, A](e: E2): F[A] = outer.raise(Error.Store(e))

  private def blocking[F[_]: Async, A](
      partition: Partition
  )(body: => A)(using Raise[F, Error]): F[A] =
    Async[F]
      .blocking(body)
      .attempt
      .flatMap(_.fold(e => Error.Sql(partition, e).raise[F, A], Async[F].pure))

  /** How a file is opened. An import opens the source read-only, and as immutable while it has no
    * write-ahead log to read: a read-only connection to a file in WAL mode creates the `-shm` and
    * `-wal` files beside it, and cannot where the directory is read-only. A log with frames holds
    * commits the file does not, so a source that has one is read through it, read-only.
    */
  private enum Access derives CanEqual:
    case Write, ReadOnly, Immutable

  private def connection[F[_]: Async](path: Path, partition: Partition, access: Access)(using
      Raise[F, Error]
  ): Resource[F, Connection] =
    Resource.make(blocking(partition) {
      val config = new SQLiteConfig()
      config.setReadOnly(access != Access.Write)
      val url = access match
        case Access.Immutable => "jdbc:sqlite:" + path.toNioPath.toUri.toString + "?immutable=1"
        case _                => "jdbc:sqlite:" + path.toString
      config.createConnection(url)
    })(c => Async[F].blocking(c.close()))

  /** The reference's `initialiseFile` (`Impl/SQLite/Connection.hs:66-73` at 2416493b): the pragmas,
    * then the schema in one transaction, which the export's inserts then continue.
    */
  private def initialise[F[_]: Async](c: Connection, partition: Partition)(using
      Raise[F, Error]
  ): F[Unit] =
    blocking(partition) {
      scala.util.Using.resource(c.createStatement()) { s =>
        Pragmas.foreach(s.execute)
        c.setAutoCommit(false)
        statements(partition).foreach(s.executeUpdate)
        c.commit()
      }
    }

  private def commit[F[_]: Async](vol: Connection, imm: Connection)(using
      Raise[F, Error]
  ): F[Unit] =
    blocking(Partition.Volatile)(vol.commit()) *> blocking(Partition.Immutable)(imm.commit())

  /** The rows of `query`, read one at a time. */
  private def rows[F[_]: Async, A](c: Connection, partition: Partition, query: String)(
      read: ResultSet => A
  )(using Raise[F, Error]): Stream[F, A] =
    val F = Async[F]
    for
      statement <- Stream.resource(
        Resource.make(blocking(partition)(c.createStatement()))(s => F.blocking(s.close()))
      )
      results <- Stream.resource(
        Resource.make(blocking(partition)(statement.executeQuery(query)))(r =>
          F.blocking(r.close())
        )
      )
      row <- Stream
        .repeatEval(blocking(partition)(Option.when(results.next())(read(results))))
        .unNoneTerminate
    yield row
