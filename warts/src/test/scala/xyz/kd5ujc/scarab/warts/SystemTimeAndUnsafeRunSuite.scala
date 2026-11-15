// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.warts

import cats.{ApplicativeError, MonadThrow}
import cats.effect.{IO, SyncIO}
import cats.effect.std.Dispatcher
import cats.syntax.all.*
import org.wartremover.test.WartTestTraverser

/** A domain error of the fixtures: a type of ours, as it is defined under `xyz.kd5ujc.scarab`. */
final case class Boom(code: Int) extends Exception

/** A domain error that is not a `Throwable`. */
enum Problem:
  case Refused

/** A failure that holds a defect's cause, as `Fault.Defect` does. */
final case class Defect(cause: Throwable)

/** Each kind of site of the wart, and what it does not report. A fixture is typed when the suite
  * compiles and never runs, so its clock readings and `unsafeRun*` calls are the wart's input, not
  * the suite's code.
  */
class SystemTimeAndUnsafeRunSuite extends munit.FunSuite:

  private def kinds(r: WartTestTraverser.Result): List[String] =
    r.errors.map(_.stripPrefix("[wartremover:SystemTimeAndUnsafeRun] ").takeWhile(_ != ':'))

  test("each message names its rule"):
    val r = WartTestTraverser(SystemTimeAndUnsafeRun) {
      val a = System.nanoTime()
      val b = IO.raiseError[Unit](new IllegalStateException("made here"))
      (a, b, (d: Dispatcher[IO]) => d.unsafeRunAndForget(IO.unit))
    }
    assertEquals(
      r.errors.map(
        _.stripPrefix("[wartremover:SystemTimeAndUnsafeRun] ").dropWhile(_ != ':').drop(2)
      ),
      List(Rules.Eff6, Rules.Err4, Rules.Eff5)
    )

  test("the wall clock"):
    val r = WartTestTraverser(SystemTimeAndUnsafeRun) {
      val a = System.nanoTime()
      val b = System.currentTimeMillis()
      val c = java.time.Instant.now()
      val d = java.time.LocalDateTime.now()
      val e = java.time.Clock.systemUTC()
      val f = java.time.Clock.systemDefaultZone()
      val g = java.time.LocalDate.now()
      val h = java.time.ZonedDateTime.now()
      val i = java.time.InstantSource.system()
      val j = java.util.Calendar.getInstance()
      val k = new java.util.Date()
      val l = scala.concurrent.duration.Deadline.now
      val m = java.time.Clock.tickMillis(java.time.ZoneOffset.UTC)
      (a, b, c, d, e, f, g, h, i, j, k, l, m)
    }
    assertEquals(kinds(r), List.fill(13)("wall-clock"))

  test("a clock passed in, and a date of a given time, are not sites"):
    val r = WartTestTraverser(SystemTimeAndUnsafeRun) {
      (
        (c: java.time.Clock) => c.instant(),
        new java.util.Date(0L),
        java.time.Instant.ofEpochSecond(1L)
      )
    }
    assertEquals(r.errors, Nil)

  test("an imported clock method"):
    val r = WartTestTraverser(SystemTimeAndUnsafeRun) {
      import java.lang.System.nanoTime
      nanoTime()
    }
    assertEquals(kinds(r), List("wall-clock"))

  test("sys.error, Predef.require, assume and assert, but not munit's assert"):
    val r = WartTestTraverser(SystemTimeAndUnsafeRun) {
      def check(ok: Boolean): Unit =
        if !ok then sys.error("no")
        scala.Predef.require(ok)
        scala.Predef.assume(ok)
        scala.Predef.assert(ok, "one is less")
        scala.Predef.assert(ok)
        scala.Predef.assert(true)
        this.assert(ok)
      check(1 < 2)
    }
    // The constant assertion is folded away by the typer and is not a site.
    assertEquals(
      kinds(r),
      List("sys-error", "predef-require", "predef-assume", "predef-assert", "predef-assert")
    )

  test("unsafeRun on IO, SyncIO and Dispatcher, written or imported"):
    val r = WartTestTraverser(SystemTimeAndUnsafeRun) {
      import cats.effect.unsafe.implicits.global
      val a = IO.unit.unsafeRunSync()
      val b = SyncIO.unit.unsafeRunSync()
      val c = IO.unit.unsafeToFuture()
      val d = (dispatcher: Dispatcher[IO]) => dispatcher.unsafeRunAndForget(IO.unit)
      val e = (dispatcher: Dispatcher[IO]) =>
        import dispatcher.unsafeRunSync
        unsafeRunSync(IO.unit)
      (a, b, c, d, e)
    }
    assertEquals(kinds(r), List.fill(5)("unsafe-run"))

  test("raiseError of a new Throwable: on a receiver, through the syntax, unqualified, in a block"):
    val r = WartTestTraverser(SystemTimeAndUnsafeRun) {
      def unqualified[F[_]](using F: MonadThrow[F]): F[Unit] =
        import F.raiseError
        raiseError(new IllegalStateException("made here"))
      val a = IO.raiseError(new IllegalStateException("made here"))
      val b = new IllegalArgumentException("made here").raiseError[IO, Unit]
      val c = IO.raiseError {
        val m = "made"
        new IllegalStateException(m)
      }
      (unqualified[IO], a, b, c)
    }
    assertEquals(kinds(r), List.fill(4)("raise-constructed"))

  test("raiseError of a Throwable a factory or a val made"):
    val r = WartTestTraverser(SystemTimeAndUnsafeRun) {
      def shutdown: Throwable = new IllegalStateException("supervisor already shutdown")
      def failure(what: String): Throwable = new IllegalArgumentException(what)
      val held: Throwable = new IllegalStateException("held")
      val again = held
      val a = IO.raiseError(shutdown)
      val b = IO.raiseError(failure("x"))
      val c = IO.raiseError(held)
      val d = IO.raiseError(again)
      val e = IO.raiseError(Option(held).getOrElse(held))
      (a, b, c, d, e)
    }
    assertEquals(kinds(r), List.fill(5)("raise-constructed"))

  test("a field that holds a made Throwable, defined after its raise"):
    val r = WartTestTraverser(SystemTimeAndUnsafeRun) {
      object Supervisor:
        def refuse: IO[Unit] = IO.raiseError(closed)
        val closed: Throwable = new IllegalStateException("closed")
      Supervisor.refuse
    }
    assertEquals(kinds(r), List("raise-constructed"))

  test("a branch of an if or a match that makes one"):
    val r = WartTestTraverser(SystemTimeAndUnsafeRun) { (e: Throwable, b: Boolean) =>
      val a = IO.raiseError(if b then e else new IllegalStateException("made"))
      val c = IO.raiseError(e match
        case _: IllegalStateException => e
        case _                        => new IllegalArgumentException("made"))
      (a, c)
    }
    assertEquals(kinds(r), List.fill(2)("raise-constructed"))

  test("raiseWhen, raiseUnless and fromOption of a made Throwable"):
    val r = WartTestTraverser(SystemTimeAndUnsafeRun) {
      def f[F[_]](o: Option[Int])(using F: MonadThrow[F]): F[Int] =
        F.raiseWhen(o.isEmpty)(new IllegalStateException("empty")) *>
          F.raiseUnless(o.nonEmpty)(new IllegalStateException("empty")) *>
          F.fromOption(o, new IllegalStateException("empty"))
      f[IO](Some(1))
    }
    assertEquals(kinds(r), List.fill(3)("raise-constructed"))

  test("a domain error, held or made, Throwable or not, and an Either of one"):
    val r = WartTestTraverser(SystemTimeAndUnsafeRun) {
      type Checked[A] = Either[Problem, A]
      val boom = Boom(1)
      val a = IO.raiseError(boom)
      val b = IO.raiseError(Boom(2))
      val c = ApplicativeError[Checked, Problem].raiseError[Unit](Problem.Refused)
      val d = (e: Either[Boom, Int]) => IO.fromEither(e)
      val g = boom.raiseError[IO, Unit]
      (a, b, c, d, g)
    }
    assertEquals(kinds(r), List.fill(5)("raise-domain-error"))

  test("a Throwable passed on as it came is not a site"):
    val r = WartTestTraverser(SystemTimeAndUnsafeRun) {
      val a = (e: Throwable) => IO.raiseError(e)
      val b = (o: Either[Throwable, Int]) =>
        o match
          case Left(t)  => IO.raiseError[Int](t)
          case Right(n) => IO.pure(n)
      val c = (d: Defect) => IO.raiseError(d.cause)
      val d = (e: Either[Throwable, Int]) => IO.fromEither(e)
      val g = IO.unit.handleErrorWith(e => IO.raiseError(e))
      (a, b, c, d, g)
    }
    assertEquals(r.errors, Nil)

  test("a definition that carries the wart's name in @SuppressWarnings is skipped"):
    val r = WartTestTraverser(SystemTimeAndUnsafeRun) {
      @SuppressWarnings(Array("xyz.kd5ujc.scarab.warts.SystemTimeAndUnsafeRun"))
      def measured: Long = System.nanoTime()
      @SuppressWarnings(Array("xyz.kd5ujc.scarab.warts.SystemTimeAndUnsafeRun"))
      object Probe:
        def start: Long = System.nanoTime()
      def unmarked: Long = System.nanoTime()
      (measured, Probe.start, unmarked)
    }
    assertEquals(kinds(r), List("wall-clock"))

  test("fromEither of a Left made here, held in a val, or of a domain error widened to Throwable"):
    val r = WartTestTraverser(SystemTimeAndUnsafeRun) {
      val held: Either[Throwable, Unit] = Left(new IllegalStateException("held"))
      val a = IO.fromEither[Unit](Left(new IllegalArgumentException("made here")))
      val b = IO.fromEither[Unit](held)
      val c = IO.fromEither[Unit](Left(Boom(1): Throwable))
      val d = IO.fromEither[Unit](new IllegalStateException("made").asLeft[Unit])
      (a, b, c, d)
    }
    assertEquals(
      kinds(r),
      List("raise-constructed", "raise-constructed", "raise-domain-error", "raise-constructed")
    )

  test("the arguments of a call to a definition kept by @SuppressWarnings are checked"):
    val r = WartTestTraverser(SystemTimeAndUnsafeRun) {
      @SuppressWarnings(Array("xyz.kd5ujc.scarab.warts.SystemTimeAndUnsafeRun"))
      def kept(n: Long): Long = n
      @SuppressWarnings(Array("xyz.kd5ujc.scarab.warts.SystemTimeAndUnsafeRun"))
      object Kept:
        def apply(n: Long): Long = n
      (kept(System.nanoTime()), Kept(System.currentTimeMillis()))
    }
    assertEquals(kinds(r), List.fill(2)("wall-clock"))

  test("fromEither of a Left made in a branch of an if or a match, direct or held in a val"):
    val r = WartTestTraverser(SystemTimeAndUnsafeRun) {
      def rejected[F[_]](reject: Boolean)(using F: MonadThrow[F]): F[Unit] =
        val result: Either[Throwable, Unit] =
          if reject then Left(new IllegalArgumentException("rejected"))
          else Right(())
        F.fromEither(result)
      def matched[F[_]](n: Int)(using F: MonadThrow[F]): F[Unit] =
        F.fromEither(n match
          case 0 => Left(new IllegalStateException("zero"))
          case _ => Right(()))
      def forwarded[F[_]](e: Throwable, fail: Boolean)(using F: MonadThrow[F]): F[Unit] =
        F.fromEither(if fail then Left(e) else Right(()))
      (rejected[IO](true), matched[IO](0), forwarded[IO](new IllegalStateException("given"), true))
    }
    assertEquals(kinds(r), List("raise-constructed", "raise-constructed"))

  test("fromEither of a union whose members include an Either of a domain error, in either order"):
    val r = WartTestTraverser(SystemTimeAndUnsafeRun) {
      val a = (e: Either[java.io.IOException, Unit] | Either[Boom, Unit]) => IO.fromEither(e)
      val b = (e: Either[Boom, Unit] | Either[java.io.IOException, Unit]) => IO.fromEither(e)
      val c = (e: Either[java.io.IOException, Unit] | Either[IllegalStateException, Unit]) =>
        IO.fromEither(e)
      (a, b, c)
    }
    assertEquals(kinds(r), List.fill(2)("raise-domain-error"))
