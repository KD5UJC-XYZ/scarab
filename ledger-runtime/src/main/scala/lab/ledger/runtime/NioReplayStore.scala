// SPDX-License-Identifier: Apache-2.0
package lab.ledger.runtime

import cats.effect.{Async, Resource}
import cats.effect.std.Semaphore
import cats.syntax.all.*
import java.nio.ByteBuffer
import java.nio.channels.{FileChannel, FileLock, OverlappingFileLockException}
import java.nio.file.{
  AtomicMoveNotSupportedException,
  Files,
  LinkOption,
  NoSuchFileException,
  Path,
  StandardCopyOption,
  StandardOpenOption
}
import java.nio.file.attribute.BasicFileAttributes
import java.util.UUID
import lab.cbor.Bytes
import lab.ledger.RestrictedReplay as R
import ReplayStore.*
import ReplayCodec.*

/** Bounded local interpreter for a trusted exclusively owned directory. Java file locking is
  * advisory; symlink checks are fail-closed checks, not protection from hostile concurrent
  * filesystem changes.
  */
object NioReplayStore:
  enum Phase:
    case BeforeObjectWrite, ObjectPartWritten, ObjectWritten, BeforeObjectForce, ObjectForced,
      BeforeObjectInstall, ObjectInstalled, BeforeObjectDirectoryForce, ObjectDirectoryForced,
      BeforeObjectStagingForce, ObjectStagingForced, BeforeHeadWrite,
      HeadPartWritten, HeadWritten, BeforeHeadForce, HeadForced, BeforeHeadReplace,
      HeadReplaced, BeforeHeadDirectoryForce, HeadDirectoryForced, BeforeHeadStagingForce,
      HeadStagingForced, BeforePublication, Published
  private val noFault: Phase => Unit = _ => ()

  private def fail(kind: StorageFailure, message: String): Nothing =
    throw new StorageException(kind, message)
  private def checked[A](result: R.Checked[A]): A =
    result.fold(f => corrupt(s"pure replay failed: $f"), identity)
  private def initial(checkpoint: Checkpoint): R.State =
    checked(R.environment(checkpoint.parameters).flatMap { env =>
      R.initialize(env, checkpoint.originalUtxo, checkpoint.attributionDigest)
    })

  /** Preserve the original failed write/force/lock error if closing also fails. */
  private[runtime] def closing[A, C <: AutoCloseable](resource: C)(body: C => A): A =
    var primary: Throwable = null
    try body(resource)
    catch
      case error: Throwable =>
        primary = error
        throw error
    finally
      try resource.close()
      catch
        case closeError: Throwable =>
          if primary == null then throw closeError
          else if closeError ne primary then primary.addSuppressed(closeError)

  private def attributes(path: Path): BasicFileAttributes =
    Files.readAttributes(path, classOf[BasicFileAttributes], LinkOption.NOFOLLOW_LINKS)
  private def optionalAttributes(path: Path): Option[BasicFileAttributes] =
    try Some(attributes(path))
    catch case _: NoSuchFileException => None
  private def noLinks(path: Path): Path =
    val absolute = path.toAbsolutePath.normalize()
    var current = absolute.getRoot
    val parts = absolute.iterator()
    while parts.hasNext do
      current = current.resolve(parts.next())
      optionalAttributes(current).foreach { a =>
        if a.isSymbolicLink then fail(StorageFailure.InvalidDirectory, "symlink in store path")
      }
    absolute
  private def read(path: Path, maximum: Long): Array[Byte] =
    val attr =
      try attributes(path)
      catch case _: NoSuchFileException => corrupt("missing referenced store file")
    if !attr.isRegularFile || attr.size() > maximum then corrupt("store file shape or byte bound")
    closing(FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) { channel =>
      val size = attr.size().toInt
      val buffer = ByteBuffer.allocate(size)
      var eof = false
      while buffer.hasRemaining && !eof do eof = channel.read(buffer) < 0
      if buffer.position() != size || channel.size() != size then
        corrupt("store file changed while reading")
      buffer.flip()
      val raw = new Array[Byte](size)
      buffer.get(raw)
      raw
    }
  private def syncDirectory(path: Path): Unit =
    closing(FileChannel.open(path, StandardOpenOption.READ))(_.force(true))

  private final class Owner(val root: Path, channel: FileChannel, lock: FileLock):
    private var active = true
    def use[A](body: => A): A = synchronized {
      if !active then fail(StorageFailure.Closed, "owner was released")
      body
    }
    def close(): Unit = synchronized {
      active = false
      closing(channel)(_ => lock.release())
    }
  private def acquire(root: Path, create: Boolean): Owner =
    val path = noLinks(root)
    optionalAttributes(path) match
      case None if create => Files.createDirectory(path)
      case None           => fail(StorageFailure.InvalidDirectory, "store directory does not exist")
      case Some(a) if !a.isDirectory => fail(StorageFailure.InvalidDirectory, "not a directory")
      case _                         => ()
    if create then
      closing(Files.newDirectoryStream(path)) { entries =>
        if entries.iterator().hasNext then
          fail(StorageFailure.InvalidDirectory, "new store must be empty")
      }
    val lockPath = path.resolve("lock")
    optionalAttributes(lockPath) match
      case None if !create => fail(StorageFailure.InvalidDirectory, "missing owner lock")
      case Some(a) if !a.isRegularFile || a.size() != 0 =>
        fail(StorageFailure.InvalidDirectory, "invalid owner lock")
      case _ => ()
    val channel =
      if create then
        FileChannel.open(
          lockPath,
          StandardOpenOption.CREATE_NEW,
          StandardOpenOption.WRITE,
          LinkOption.NOFOLLOW_LINKS
        )
      else FileChannel.open(lockPath, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)
    try
      val lock =
        try channel.tryLock()
        catch case _: OverlappingFileLockException => null
      if lock == null then fail(StorageFailure.Locked, "another owner holds the store lock")
      new Owner(path, channel, lock)
    catch
      case error: Throwable =>
        try channel.close()
        catch
          case closeError: Throwable => if closeError ne error then error.addSuppressed(closeError)
        throw error

  def create[F[_]: Async](
      root: Path,
      checkpoint: Checkpoint,
      limits: Limits = Limits(),
      fault: Phase => Unit = noFault
  ): Resource[F, ReplayStore[F]] = resource(root, Some(checkpoint), limits, fault)

  def open[F[_]: Async](
      root: Path,
      limits: Limits = Limits(),
      fault: Phase => Unit = noFault
  ): Resource[F, ReplayStore[F]] = resource(root, None, limits, fault)

  private def resource[F[_]: Async](
      root: Path,
      checkpoint: Option[Checkpoint],
      limits: Limits,
      fault: Phase => Unit
  ): Resource[F, ReplayStore[F]] =
    val F = Async[F]
    for
      _ <- Resource.eval(F.delay {
        limits.validate()
        checkpoint.foreach(initial)
      })
      owner <- Resource.make(F.blocking(acquire(root, checkpoint.nonEmpty)))(o =>
        F.blocking(o.close())
      )
      semaphore <- Resource.eval(Semaphore[F](1))
      work <- Resource.eval(Semaphore[F](1))
      disk <- Resource.eval(F.blocking {
        owner.use {
          val d = new Disk(owner.root, UUID.randomUUID().toString, limits, fault)
          checkpoint match
            case Some(value) => d.initialize(value)
            case None        => d.recover()
          d
        }
      })
    yield new ReplayStore[F]:
      def snapshot: F[Snapshot] = semaphore.permit.use(_ => F.blocking(owner.use(disk.current)))
      def commit(expected: Version, transactions: Vector[Bytes]): F[Either[Rejection, Snapshot]] =
        work.permit.use { _ =>
          snapshot.flatMap { before =>
            if !before.version.matches(expected) then F.pure(Left(Rejection.StaleVersion))
            else
              F.delay(R.applyBatch(before.state, transactions)).flatMap {
                case Left(reason)   => F.pure(Left(Rejection.Ledger(reason)))
                case Right(applied) =>
                  // Pure verification and waiting for the writer are cancelable. Publication is one
                  // masked bounded action; a canceled caller must reopen/read rather than assume abort.
                  F.cede *> semaphore.permit.use { _ =>
                    F.uncancelable(_ => F.blocking(owner.use(disk.commit(expected, applied))))
                  }
              }
          }
        }
      def rollback(expected: Version, transition: Bytes): F[Either[Rejection, Snapshot]] =
        work.permit.use { _ =>
          snapshot.flatMap { before =>
            if !before.version.matches(expected) then F.pure(Left(Rejection.StaleVersion))
            else
              before.undo match
                case Some(delta) if delta.transitionId == transition =>
                  F.delay(R.undo(before.state, before.state.revision, delta)).flatMap {
                    case Left(reason) => F.pure(Left(Rejection.Ledger(reason)))
                    case Right(next) =>
                      F.cede *> semaphore.permit.use { _ =>
                        F.uncancelable(_ =>
                          F.blocking(owner.use(disk.rollback(expected, transition, next)))
                        )
                      }
                  }
                case _ => F.pure(Left(Rejection.RollbackMismatch))
          }
        }

  private final case class View(
      snapshot: Snapshot,
      stack: Vector[R.BatchDelta],
      recoveryBytes: Long,
      transactions: Int,
      evidenceBytes: Long
  )
  private final class Disk(root: Path, session: String, limits: Limits, fault: Phase => Unit):
    private var value: Option[View] = None
    private var seedId = Bytes.empty
    private var poisoned = false
    private val objects = root.resolve("objects")
    private val staging = root.resolve("staging")
    private def live: View =
      if poisoned then fail(StorageFailure.RequiresReopen, "interrupted or ambiguous publication")
      value.getOrElse(fail(StorageFailure.RequiresReopen, "uninitialized owner"))
    def current: Snapshot = live.snapshot
    private def snapshot(
        state: R.State,
        tip: Bytes,
        stack: Vector[R.BatchDelta],
        count: Int
    ): Snapshot =
      new Snapshot(state, new Version(session, tip, state.revision.number), stack.lastOption, count)
    private def path(id: Bytes): Path = objects.resolve(s"${id.hex}.bin")
    private def objectBytes(id: Bytes, remaining: Long): Array[Byte] =
      val raw = read(path(id), math.min(MaxObjectBytes.toLong, remaining))
      if sha(raw) != id then corrupt("immutable object SHA256 mismatch")
      raw
    private def inventory(): (Long, Int) =
      var bytes = 0L
      var files = 0
      def visit(directory: Path, kind: String): Unit =
        closing(Files.newDirectoryStream(directory)) { stream =>
          val iterator = stream.iterator()
          while iterator.hasNext do
            val p = iterator.next()
            val a = attributes(p)
            val name = p.getFileName.toString
            if kind == "root" && Set("objects", "staging")(name) then
              if !a.isDirectory then fail(StorageFailure.InvalidDirectory, "unsafe store directory")
              visit(p, name)
            else
              val allowed = kind match
                case "root"    => Set("lock", "head")(name)
                case "objects" => name.matches("[0-9a-f]{64}\\.bin")
                case "staging" => Set("object.tmp", "head.tmp")(name)
                case _         => false
              if !allowed || !a.isRegularFile then
                fail(StorageFailure.InvalidDirectory, "unrelated or unsafe store entry")
              if a.size() > limits.maxStoredBytes - bytes then
                corrupt("stored byte budget exceeded")
              bytes += a.size()
              files += 1
              if files > limits.maxFiles then corrupt("stored file budget exceeded")
        }
      visit(root, "root")
      (bytes, files)
    private def capacity(additional: Long, newFiles: Int): Either[Rejection, Unit] =
      val (bytes, files) = inventory()
      Either.cond(
        additional <= limits.maxStoredBytes - bytes && newFiles <= limits.maxFiles - files,
        (),
        Rejection.ResourceLimit("directory quota, including abandoned objects and staging")
      )
    private def write(temp: Path, raw: Array[Byte], objectFile: Boolean): Unit =
      optionalAttributes(temp).foreach { a =>
        if !a.isRegularFile then fail(StorageFailure.InvalidDirectory, "unsafe staging entry")
      }
      val options: Vector[java.nio.file.OpenOption] = optionalAttributes(temp) match
        case Some(_) =>
          Vector(
            StandardOpenOption.WRITE,
            StandardOpenOption.TRUNCATE_EXISTING,
            LinkOption.NOFOLLOW_LINKS
          )
        case None =>
          Vector(StandardOpenOption.WRITE, StandardOpenOption.CREATE_NEW, LinkOption.NOFOLLOW_LINKS)
      closing(FileChannel.open(temp, options*)) { channel =>
        val first = ByteBuffer.wrap(raw, 0, raw.length / 2)
        while first.hasRemaining do channel.write(first)
        fault(if objectFile then Phase.ObjectPartWritten else Phase.HeadPartWritten)
        val rest = ByteBuffer.wrap(raw, raw.length / 2, raw.length - raw.length / 2)
        while rest.hasRemaining do channel.write(rest)
        fault(if objectFile then Phase.ObjectWritten else Phase.HeadWritten)
        fault(if objectFile then Phase.BeforeObjectForce else Phase.BeforeHeadForce)
        channel.force(true)
      }
      fault(if objectFile then Phase.ObjectForced else Phase.HeadForced)
    private def atomicMove(from: Path, to: Path, replace: Boolean): Unit =
      try
        if replace then
          Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        else Files.move(from, to, StandardCopyOption.ATOMIC_MOVE)
      catch
        case _: AtomicMoveNotSupportedException =>
          fail(
            StorageFailure.UnsupportedFilesystem,
            "atomic replacement is required; no copy fallback"
          )
    private def install(raw: Array[Byte]): Bytes =
      val id = sha(raw)
      val target = path(id)
      fault(Phase.BeforeObjectWrite)
      write(staging.resolve("object.tmp"), raw, true)
      fault(Phase.BeforeObjectInstall)
      optionalAttributes(target) match
        case Some(_) =>
          if !java.util.Arrays.equals(read(target, MaxObjectBytes), raw) then
            corrupt("immutable object collision")
          Files.delete(staging.resolve("object.tmp"))
        case None => atomicMove(staging.resolve("object.tmp"), target, false)
      fault(Phase.ObjectInstalled)
      fault(Phase.BeforeObjectDirectoryForce)
      syncDirectory(objects)
      fault(Phase.ObjectDirectoryForced)
      fault(Phase.BeforeObjectStagingForce)
      syncDirectory(staging)
      fault(Phase.ObjectStagingForced)
      id
    private def publishHead(tip: Bytes, state: R.State, count: Int): Unit =
      fault(Phase.BeforeHeadWrite)
      write(
        staging.resolve("head.tmp"),
        head(Head(seedId, tip, state.revision.number, count)),
        false
      )
      fault(Phase.BeforeHeadReplace)
      atomicMove(staging.resolve("head.tmp"), root.resolve("head"), true)
      fault(Phase.HeadReplaced)
      fault(Phase.BeforeHeadDirectoryForce)
      syncDirectory(root)
      fault(Phase.HeadDirectoryForced)
      fault(Phase.BeforeHeadStagingForce)
      syncDirectory(staging)
      fault(Phase.HeadStagingForced)
    private def mutation[A](body: => A): A =
      try body
      catch
        case error: Throwable =>
          poisoned = true
          throw error

    def initialize(checkpoint: Checkpoint): Unit =
      val state = initial(checkpoint)
      val raw = seed(checkpoint)
      if raw.length > limits.maxRecoveryBytes then corrupt("initial recovery byte budget")
      capacity(raw.length.toLong + MaxHeadBytes, 2).fold(r => corrupt(r.toString), identity)
      mutation {
        Files.createDirectory(objects)
        Files.createDirectory(staging)
        syncDirectory(root)
        seedId = install(raw)
        publishHead(seedId, state, 0)
        fault(Phase.BeforePublication)
        value =
          Some(View(snapshot(state, seedId, Vector.empty, 0), Vector.empty, raw.length, 0, 0L))
        fault(Phase.Published)
      }
    def recover(): Unit =
      inventory()
      if !attributes(objects).isDirectory || !attributes(staging).isDirectory then
        corrupt("missing store directories")
      // An absent or corrupt selected head is never repaired, guessed from orphans, or rolled back.
      val selected = readHead(read(root.resolve("head"), MaxHeadBytes))
      if selected.count > limits.maxHistory then corrupt("history budget on reopen")
      seedId = selected.seed
      val seedRaw = objectBytes(seedId, limits.maxRecoveryBytes)
      var recoveredBytes = seedRaw.length.toLong
      if recoveredBytes > limits.maxRecoveryBytes then corrupt("recovery byte budget")
      val state = initial(readSeed(seedRaw))
      var tip = selected.tip
      var records = Vector.empty[(Bytes, Record)]
      var transactionCount = 0
      while tip != seedId do
        if records.size >= selected.count || records.size >= limits.maxHistory then
          corrupt("journal ancestry bound or cycle")
        val raw = objectBytes(tip, limits.maxRecoveryBytes - recoveredBytes)
        if raw.length > limits.maxRecoveryBytes - recoveredBytes then
          corrupt("recovery byte budget")
        recoveredBytes += raw.length
        val record = readRecord(raw)
        if record.seed != seedId then corrupt("journal checkpoint mismatch")
        record.operation match
          case Operation.Apply(txs) =>
            transactionCount += txs.size
            if transactionCount > limits.maxTransactions then corrupt("recovery transaction budget")
          case _ => ()
        records = records :+ (tip -> record)
        tip = record.parent
      if records.size != selected.count then corrupt("journal ancestry count mismatch")
      var currentState = state
      var stack = Vector.empty[R.BatchDelta]
      var retained = 0L
      records.reverse.foreach { (_, record) =>
        record.operation match
          case Operation.Apply(txs) =>
            val applied = checked(R.applyBatch(currentState, txs))
            val delta = applied.delta.getOrElse(corrupt("recorded no-op"))
            val extra = evidence(delta)
            if extra > limits.maxEvidenceBytes - retained then corrupt("recovery evidence budget")
            retained += extra
            stack = stack :+ delta
            currentState = applied.state
          case Operation.Undo(transition) =>
            val delta = stack.lastOption.getOrElse(corrupt("rollback without predecessor"))
            if delta.transitionId != transition then corrupt("rollback transition mismatch")
            currentState = checked(R.undo(currentState, currentState.revision, delta))
            stack = stack.dropRight(1)
        if currentState.revision.number != record.revision || currentState.stateId != record.stateId ||
          currentState.feesSinceCheckpoint != record.fees || currentState.outputMap != record.outputMap ||
          stack.lastOption.map(_.transitionId) != record.top
        then corrupt("persisted projection summary disagrees with checked replay")
      }
      if currentState.revision.number != selected.revision then corrupt("head revision mismatch")
      value = Some(
        View(
          snapshot(currentState, selected.tip, stack, selected.count),
          stack,
          recoveredBytes,
          transactionCount,
          retained
        )
      )

    private def publish(
        before: View,
        next: R.State,
        stack: Vector[R.BatchDelta],
        operation: Operation,
        txCount: Int,
        extraEvidence: Long
    ): Either[Rejection, Snapshot] =
      if before.snapshot.historyLength >= limits.maxHistory then
        Left(Rejection.ResourceLimit("history length"))
      else if txCount > limits.maxTransactions - before.transactions then
        Left(Rejection.ResourceLimit("cumulative replay transactions"))
      else if extraEvidence > limits.maxEvidenceBytes - before.evidenceBytes then
        Left(Rejection.ResourceLimit("cumulative replay evidence"))
      else
        val record = Record(
          seedId,
          before.snapshot.version.head,
          operation,
          next.revision.number,
          next.stateId,
          next.feesSinceCheckpoint,
          next.outputMap,
          stack.lastOption.map(_.transitionId)
        )
        val raw = ReplayCodec.record(record)
        if raw.length > limits.maxRecoveryBytes - before.recoveryBytes then
          Left(Rejection.ResourceLimit("cumulative recovery bytes"))
        else
          capacity(raw.length.toLong + MaxHeadBytes, 2).map { _ =>
            mutation {
              val tip = install(raw)
              val count = before.snapshot.historyLength + 1
              publishHead(tip, next, count)
              fault(Phase.BeforePublication)
              val result = snapshot(next, tip, stack, count)
              value = Some(
                View(
                  result,
                  stack,
                  before.recoveryBytes + raw.length,
                  before.transactions + txCount,
                  before.evidenceBytes + extraEvidence
                )
              )
              fault(Phase.Published)
              result
            }
          }
    def commit(expected: Version, applied: R.BatchApplied): Either[Rejection, Snapshot] =
      val before = live
      if !before.snapshot.version.matches(expected) then Left(Rejection.StaleVersion)
      else
        applied.delta match
          case None => Right(before.snapshot)
          case Some(delta) =>
            publish(
              before,
              applied.state,
              before.stack :+ delta,
              Operation.Apply(delta.transactions.map(_.originalTransaction)),
              delta.transactions.size,
              evidence(delta)
            )
    def rollback(expected: Version, transition: Bytes, next: R.State): Either[Rejection, Snapshot] =
      val before = live
      if !before.snapshot.version.matches(expected) then Left(Rejection.StaleVersion)
      else
        before.stack.lastOption match
          case Some(delta) if delta.transitionId == transition =>
            publish(before, next, before.stack.dropRight(1), Operation.Undo(transition), 0, 0L)
          case _ => Left(Rejection.RollbackMismatch)
