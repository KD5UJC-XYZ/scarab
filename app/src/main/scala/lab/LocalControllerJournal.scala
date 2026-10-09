// SPDX-License-Identifier: Apache-2.0
package lab

import cats.effect.{Async, Ref, Resource}
import cats.effect.std.Semaphore
import cats.syntax.all.*
import java.nio.ByteBuffer
import java.nio.channels.{FileChannel, FileLock, OverlappingFileLockException}
import java.nio.file.{
  Files,
  Path,
  LinkOption,
  StandardOpenOption as O,
  StandardCopyOption as C,
  FileAlreadyExistsException
}
import lab.cbor.Bytes
import ControllerReducer.*
import ControllerJournalCodec.*
import scala.util.control.NonFatal

/** One local controller journal and one immutable store mapping. External effects are returned,
  * never executed. The caller is a trusted in-process adapter, not an authenticated remote peer.
  */
object LocalControllerJournal:
  enum Mode:
    case Create, Resume
  enum Phase:
    case BeforeOpen, Opened, WriteChunk, Written, BeforeFileForce, FileForced,
      FileClosed, BeforeReplace, Replaced, BeforeDirectoryForce, DirectoryForced
  trait Faults:
    def forceParent(path: Path): Unit = sync(path)
    def at(phase: Phase): Unit = ()
    def write(channel: FileChannel, buffer: ByteBuffer): Int = channel.write(buffer)
    def replace(from: Path, to: Path): Unit =
      Files.move(from, to, C.ATOMIC_MOVE, C.REPLACE_EXISTING): Unit
  object NoFaults extends Faults
  final case class Result(journal: Journal, reply: Reply, effects: Vector[Effect])
  private final case class State(image: Image, machine: Machine, closed: Boolean = false)
  private def get[A](v: Either[String, A]): A =
    v.fold(s => throw new IllegalArgumentException(s), identity)
  private def noLinks(path: Path): Path =
    require(path.isAbsolute && path.normalize == path, "canonical absolute path")
    var p = path
    while p != null do
      require(!Files.isSymbolicLink(p), "symbolic link")
      p = p.getParent
    path
  private def sync(path: Path): Unit =
    val c = FileChannel.open(path, O.READ)
    try c.force(true)
    finally c.close()
  private final class DiskStore(
      val binding: Binding,
      channel: FileChannel,
      lock: FileLock,
      faults: Faults
  ):
    private val root = Path.of(binding.root)
    private val published = root.resolve("controller.bin")
    private val staging = root.resolve("controller.tmp")
    private var closed = false
    private var poisoned = false
    private def active(): Unit = require(!closed && !poisoned, "journal closed or poisoned")
    private def inventory(): Unit =
      noLinks(root); noLinks(Path.of(binding.checkpoint))
      val entries = Files.newDirectoryStream(root)
      try
        val it = entries.iterator(); var count = 0
        while it.hasNext do
          val p = noLinks(it.next()); val name = p.getFileName.toString
          require(
            Set("lock", "controller.bin", "controller.tmp")(name) && Files
              .isRegularFile(p, LinkOption.NOFOLLOW_LINKS),
            "unexpected journal entry"
          )
          require(Files.size(p) <= MaxBytes && (name != "lock" || Files.size(p) == 0), "file bound")
          count += 1; require(count <= 3, "directory bound")
      finally entries.close()
    def read(): Bytes =
      active(); inventory()
      val c = FileChannel.open(noLinks(published), O.READ, LinkOption.NOFOLLOW_LINKS)
      try
        val n = c.size(); require(n >= 32 && n <= MaxBytes, "journal size")
        val b = ByteBuffer.allocate(n.toInt)
        while b.hasRemaining do require(c.read(b) > 0, "short journal read")
        require(c.read(ByteBuffer.allocate(1)) == -1, "journal grew")
        Bytes.fromArray(b.array())
      finally c.close()
    def load(mode: Mode): Image =
      active(); inventory()
      mode match
        case Mode.Create =>
          require(
            !Files.exists(published, LinkOption.NOFOLLOW_LINKS) && !Files
              .exists(staging, LinkOption.NOFOLLOW_LINKS),
            "create requires empty journal"
          )
          val image = Image(binding, Journal(0, 0, Selection.Dormant(binding.store)), Vector.empty)
          install(None, get(encode(image))); image
        case Mode.Resume =>
          val image = get(decode(read(), binding))
          // An orphan temp is never an authority candidate. Only discard after strict primary load.
          if Files.exists(staging, LinkOption.NOFOLLOW_LINKS) then
            Files.delete(noLinks(staging)); sync(root)
          image
    def install(expected: Option[Bytes], next: Bytes): Unit =
      active()
      try
        inventory(); get(decode(next, binding))
        expected match
          case Some(old) => require(read() == old, "journal changed outside controller")
          case None =>
            require(!Files.exists(published, LinkOption.NOFOLLOW_LINKS), "initial journal conflict")
        require(!Files.exists(staging, LinkOption.NOFOLLOW_LINKS), "unsettled staging file")
        faults.at(Phase.BeforeOpen)
        val out =
          FileChannel.open(noLinks(staging), O.CREATE_NEW, O.WRITE, LinkOption.NOFOLLOW_LINKS)
        try
          faults.at(Phase.Opened)
          val b = ByteBuffer.wrap(next.toArray)
          while b.hasRemaining do
            val before = b.position(); val n = faults.write(out, b)
            require(n > 0 && b.position() - before == n, "stalled/invalid write")
            faults.at(Phase.WriteChunk)
          faults.at(Phase.Written); faults.at(Phase.BeforeFileForce)
          out.force(true); faults.at(Phase.FileForced)
        finally out.close()
        faults.at(Phase.FileClosed); faults.at(Phase.BeforeReplace)
        faults.replace(staging, published); faults.at(Phase.Replaced)
        faults.at(Phase.BeforeDirectoryForce); sync(root); faults.at(Phase.DirectoryForced)
      catch
        case NonFatal(e) =>
          poisoned = true
          throw e
    def close(): Unit =
      if !closed then
        closed = true
        try lock.release()
        finally channel.close()

  private def acquire(binding: Binding, mode: Mode, faults: Faults): DiskStore =
    get(validate(Image(binding, Journal(0, 0, Selection.Dormant(binding.store)), Vector.empty)))
    val root = noLinks(Path.of(binding.root)); noLinks(Path.of(binding.checkpoint))
    if !Files.exists(root, LinkOption.NOFOLLOW_LINKS) then
      require(mode == Mode.Create, "journal directory missing")
      try Files.createDirectory(root)
      catch case _: FileAlreadyExistsException => ()
    require(Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS), "journal directory required")
    // Also repairs an earlier explicit Create that created the directory but failed parent force.
    if mode == Mode.Create then faults.forceParent(root.getParent)
    val p = noLinks(root.resolve("lock"))
    val exists = Files.exists(p, LinkOption.NOFOLLOW_LINKS)
    require(!exists || Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS), "lock type")
    require(mode == Mode.Create || exists, "journal lock missing")
    val options: Vector[java.nio.file.OpenOption] =
      if mode == Mode.Create then Vector(O.CREATE, O.WRITE, LinkOption.NOFOLLOW_LINKS)
      else Vector(O.WRITE, LinkOption.NOFOLLOW_LINKS)
    val c = FileChannel.open(p, options*)
    try
      val l =
        try c.tryLock()
        catch case _: OverlappingFileLockException => null
      require(l != null, "controller already owned")
      new DiskStore(binding, c, l, faults)
    catch
      case NonFatal(e) =>
        c.close()
        throw e

  final class Controller[F[_]] private[LocalControllerJournal] (
      disk: DiskStore,
      gate: Semaphore[F],
      state: Ref[F, State]
  )(using F: Async[F]):
    /** Returns no Machine/capability. The snapshot is diagnostic durable journal data only. */
    def snapshot: F[Image] = gate.permit.use(_ => state.get.map(_.image))
    def submitBytes(bytes: Bytes): F[Either[String, Result]] =
      F.delay(decodeMessage(bytes)).flatMap {
        case Left(e)  => F.pure(Left(e))
        case Right(m) => submit(m)
      }
    def submit(message: Message): F[Either[String, Result]] =
      gate.permit.use { _ =>
        F.uncancelable { _ =>
          state.get.flatMap { s =>
            if s.closed || s.machine.halted then F.pure(Left("controller closed or poisoned"))
            else
              F.delay(
                encodeMessage(message)
                  .flatMap(decodeMessage)
                  .flatMap(m => updated(s.image, s.image.journal, m.claims).map(_ => m))
              ).flatMap {
                case Left(e) => F.pure(Left(e))
                case Right(m) =>
                  val transition = ControllerReducer.step(s.machine, m.input)
                  transition.effects match
                    case Vector(Effect.Force(ticket, proposed)) =>
                      updated(s.image, proposed, m.claims).flatMap(i => encode(i).map(i -> _)) match
                        case Left(e) => F.pure(Left(e))
                        case Right((image, bytes)) =>
                          F.blocking(disk.install(Some(get(encode(s.image))), bytes))
                            .attempt
                            .flatMap {
                              case Left(_) =>
                                val stopped = ControllerReducer.step(
                                  transition.state,
                                  Input.Completed(Evidence.ForceUncertain(ticket))
                                )
                                state
                                  .set(s.copy(machine = stopped.state))
                                  .as(
                                    Left(
                                      "journal write uncertain; controller poisoned; strict reopen required"
                                    )
                                  )
                              case Right(_) =>
                                val done = ControllerReducer.step(
                                  transition.state,
                                  Input.Completed(Evidence.Forced(ticket))
                                )
                                state
                                  .set(State(image, done.state))
                                  .as(Right(Result(image.journal, done.reply, done.effects)))
                            }
                    case _ =>
                      state
                        .set(s.copy(machine = transition.state))
                        .as(Right(Result(s.image.journal, transition.reply, transition.effects)))
              }
          }
        }
      }
    private[LocalControllerJournal] def close: F[Unit] =
      gate.permit.use { _ =>
        F.uncancelable { _ =>
          state.update(_.copy(closed = true)) *> F.blocking(disk.close())
        }
      }

  def resource[F[_]: Async](
      binding: Binding,
      mode: Mode,
      incarnation: Id,
      faults: Faults = NoFaults
  ): Resource[F, Controller[F]] =
    val F = Async[F]
    // Outer finalizer covers acquisition/load failure. Inner finalizer serializes normal close.
    Resource.eval(
      F.delay(require(incarnation != null && incarnation.valid, "controller incarnation"))
    ) *>
      Resource
        .make(F.blocking(acquire(binding, mode, faults)))(d => F.blocking(d.close()))
        .flatMap { disk =>
          for
            image <- Resource.eval(F.blocking(disk.load(mode)))
            machine <- Resource.eval(
              F.fromEither(
                ControllerReducer
                  .open(image.journal, incarnation)
                  .leftMap(new IllegalArgumentException(_))
              )
            )
            gate <- Resource.eval(Semaphore[F](1))
            state <- Resource.eval(Ref.of[F, State](State(image, machine)))
            controller <- Resource.make(F.pure(new Controller(disk, gate, state)))(_.close)
          yield controller
        }
