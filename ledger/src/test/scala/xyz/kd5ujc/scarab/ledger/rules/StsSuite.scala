// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.rules

import xyz.kd5ujc.scarab.ledger.TestGeometry
import cats.{Eq, Monad}
import cats.laws.discipline.MonadTests
import cats.mtl.{Ask, Handle, Local, Raise, Stateful}
import cats.mtl.laws.discipline.{HandleTests, LocalTests, StatefulTests}
import cats.syntax.all.*
import java.time.Instant
import monocle.{Focus, Lens}
import munit.DisciplineSuite
import org.scalacheck.{Arbitrary, Cogen, Gen}
import org.scalacheck.Prop.forAll
import xyz.kd5ujc.scarab.codec.Rational
import xyz.kd5ujc.scarab.ledger.{EpochGeometry, LedgerGlobals}

/** The rule algebra ([[Sts]]) and its interpreter ([[RuleT]]): the cats-mtl laws of the instances,
  * the lens that focuses a state, the wrapping of a sub-rule's failures, and a small system built
  * the way the ledger's are (a rule made of another through [[Embed]]).
  */
class StsSuite extends DisciplineSuite:
  import StsSuite.*

  type R[A] = RuleT[Nothing, Fail, Int, A]

  private val globals = Globals(
    LedgerGlobals(BigInt(1000), 100, Rational(1, 20), network = 0),
    EpochGeometry(Instant.EPOCH, 1000, 100, TestGeometry.k(5), TestGeometry.f("0.05"))
  )
  private val otherGlobals = globals.copy(ledger = globals.ledger.copy(network = 1))

  // Programs compare by what they do on a sample of environments and states.
  given [A: Eq]: Eq[RuleT[Nothing, Fail, Int, A]] = Eq.instance((x, y) =>
    List(globals, otherGlobals).forall(g =>
      List(0, 1, 2, -7, 100).forall(s => x.run(g, s) === y.run(g, s))
    )
  )
  given Eq[Globals] = Eq.fromUniversalEquals

  private val genFail: Gen[Fail] = Gen.oneOf(Gen.choose(0, 5).map(Fail.Odd(_)), Gen.const(Fail.Big))
  given Arbitrary[Fail] = Arbitrary(genFail)
  given Cogen[Fail] = Cogen[Int].contramap(_.toString.length)
  given Arbitrary[Globals] = Arbitrary(Gen.oneOf(globals, otherGlobals))
  given Cogen[Globals] = Cogen[Int].contramap(_.networkId)

  // A random program: reads the state, maybe changes it, maybe fails, returns a value.
  given [A: Arbitrary]: Arbitrary[RuleT[Nothing, Fail, Int, A]] = Arbitrary(
    for
      a <- Arbitrary.arbitrary[A]
      f <- Arbitrary.arbitrary[Int => Int]
      e <- genFail
      limit <- Gen.choose(-5, 120)
    yield Monad[R].flatMap(Stateful[R, Int].modify(f))(_ =>
      Monad[R].flatMap(Stateful[R, Int].get)(s =>
        if s > limit then Raise[R, Fail].raise[Fail, A](e) else Monad[R].pure(a)
      )
    )
  )

  checkAll("RuleT Monad", MonadTests[R].monad[Int, Int, Int])
  checkAll("RuleT Stateful", StatefulTests[R, Int].stateful)
  checkAll(
    "RuleT Stateful, focused through a lens",
    StatefulTests(Stateful[R, Int].focus(Lens[Int, Int](_ + 1)(i => _ => i - 1))).stateful
  )
  checkAll("RuleT Local", LocalTests[R, Globals].local[Int, Int])
  checkAll("RuleT Handle", HandleTests[R, Fail].handle[Int])

  // A few hundred thousand steps on a stack of 256 KiB: every way of sequencing rule steps must
  // run in constant stack (owner requirement: strictly typed, stack-safe code).
  private val Steps = 300000

  private def onSmallStack[A](body: => A): A =
    var result: Option[Either[Throwable, A]] = None
    val thread = new Thread(
      null,
      () =>
        result = Some(
          try Right(body)
          catch case t: Throwable => Left(t)
        ),
      "small",
      256 * 1024
    )
    thread.start()
    thread.join()
    result.fold(fail("no result"))(_.fold(throw _, identity))

  private val bump: R[Unit] = Stateful[R, Int].modify(_ + 1)

  test("tailRecM is stack safe") {
    val loop = Monad[R].tailRecM(0)(n =>
      if n < 1000000 then Monad[R].pure(Left(n + 1)) else Monad[R].pure(Right(n))
    )
    assertEquals(onSmallStack(loop.run(globals, 0)), Right((0, 1000000)))
  }

  test("a recursion through flatMap is stack safe, to the right and to the left") {
    def right(n: Int): R[Unit] = if n == 0 then Monad[R].unit else bump.flatMap(_ => right(n - 1))
    val left = (1 to Steps).foldLeft(Monad[R].unit)((acc, _) => acc.flatMap(_ => bump))
    assertEquals(onSmallStack(right(Steps).run(globals, 0)), Right((Steps, ())))
    assertEquals(onSmallStack(left.run(globals, 0)), Right((Steps, ())))
  }

  test("traverse_, traverse, foldM and sequence over a few hundred thousand steps are stack safe") {
    val xs = List.range(0, Steps)
    assertEquals(onSmallStack(xs.traverse_(_ => bump).run(globals, 0)), Right((Steps, ())))
    assertEquals(
      onSmallStack(xs.traverse(i => bump.as(i)).run(globals, 0).map((s, ys) => (s, ys.size))),
      Right((Steps, Steps))
    )
    assertEquals(
      onSmallStack(xs.foldM(0)((a, i) => bump.as(a + i)).run(globals, 0)),
      Right((Steps, xs.sum))
    )
    assertEquals(
      onSmallStack(xs.map(_ => bump).sequence_.run(globals, 0)),
      Right((Steps, ()))
    )
  }

  test("a failure at the end of a long sequence, and a handler around it, are stack safe") {
    val long =
      List.range(0, Steps).traverse_(_ => bump) *> Raise[R, Fail].raise[Fail, Unit](Fail.Big)
    assertEquals(onSmallStack(long.run(globals, 0)), Left(Fail.Big))
    val handled = Handle[R, Fail].handleWith(long)(_ => Stateful[R, Int].get.void)
    assertEquals(onSmallStack(handled.run(globals, 5)), Right((5, ())))
    // handlers nested a few thousand deep
    val nested = (1 to 5000).foldLeft(long)((p, _) =>
      Handle[R, Fail].handleWith(p)(e => Raise[R, Fail].raise[Fail, Unit](e))
    )
    assertEquals(onSmallStack(nested.run(globals, 0)), Left(Fail.Big))
  }

  test("the system adds its signal to the state and refuses what is too big") {
    assertEquals(Sts(Counter)(globals, (), Count(1, Vector.empty), 5), Right(Count(6, Vector(5))))
    assertEquals(
      Sts(Counter)(globals, (), Count(1, Vector.empty), 500),
      Left(Fail.Big)
    )
  }

  test("a failure of the embedded rule is the wrapped one, a success changes its part only") {
    // Bump runs on the `total` of the outer state; its failure is wrapped into the outer's
    val ok = Sts(Outer)(globals, (), Count(10, Vector(1)), 5)
    assertEquals(ok, Right(Count(15, Vector(1))))
    val bad = Sts(Outer)(globals, (), Count(10, Vector(1)), 1000)
    assertEquals(bad, Left(OuterFail.Inner(Fail.Big)))
    val outerFirst = Sts(Outer)(globals, (), Count(10, Vector(1)), -1)
    assertEquals(outerFirst, Left(OuterFail.Negative(-1)))
  }

  test("focus is a lens on the state and writes through") {
    forAll { (start: Int, n: Int) =>
      val program =
        Stateful[R, Int].focus(Lens[Int, Int](_ + 1)(i => _ => i - 1)).set(n)
      val expected = Right((n - 1, ()))
      assertEquals(program.run(globals, start), expected)
    }
  }

  test("a handled failure resumes from the state the failed program started from") {
    val failing = Stateful[R, Int].set(99) *> Raise[R, Fail].raise[Fail, Int](Fail.Big)
    val program = Handle[R, Fail].handleWith(failing)(_ => Stateful[R, Int].get)
    assertEquals(program.run(globals, 7), Right((7, 7)))
  }

  test("a rule asks for the globals it runs with") {
    val program = Sts.globals[R].map(_.networkId)
    assertEquals(program.run(otherGlobals, 0), Right((0, 1)))
    val local = Ask[R, Globals].ask[Globals]
    assertEquals(local.run(globals, 3).map(_._2), Right(globals))
  }

  test("nested scopes restore Globals and rollback before recovery with different result types") {
    val read = Ask[R, Globals].ask[Globals].map(_.networkId)
    def network(n: Int)(g: Globals): Globals = g.copy(ledger = g.ledger.copy(network = n))
    val failed = Local[R, Globals].local(
      Stateful[R, Int].set(99) *> Raise[R, Fail].raise[Fail, String](Fail.Big)
    )(network(1))
    val recovered = Handle[R, Fail].handleWith(failed)(_ => read.map(n => s"recovered-$n"))
    val program = for
      before <- read
      text <- Local[R, Globals].local(recovered)(network(2))
      after <- read
      state <- Stateful[R, Int].get
      success <- Local[R, Globals].local(read.map(_ == 3))(network(3))
      restored <- read
    yield (before, text, after, state, success, restored)
    assertEquals(program.run(globals, 7), Right((7, (0, "recovered-2", 0, 7, true, 0))))
  }

object StsSuite:
  /** The failure of the rules below. */
  enum Fail:
    case Odd(n: Int)
    case Big

  object Fail:
    given Eq[Fail] = Eq.fromUniversalEquals

  enum OuterFail:
    case Negative(n: Int)
    case Inner(failure: Fail)

  final case class Count(total: Int, seen: Vector[Int])

  object Count:
    val total: Lens[Count, Int] = Focus[Count](_.total)

  /** Adds the signal to a number, refusing a result over 100. */
  object Bump extends Sts[Unit, Int, Int, Fail]:
    def transition[F[_]: Monad](env: Unit, signal: Int)(using
        Ask[F, Globals],
        Stateful[F, Int],
        Raise[F, Fail]
    ): F[Unit] =
      for
        before <- Stateful[F, Int].get
        _ <- Sts.check[F, Fail](before + signal <= 100, Fail.Big)
        _ <- Stateful[F, Int].set(before + signal)
      yield ()

  /** The same on a record, remembering the signal. */
  object Counter extends Sts[Unit, Count, Int, Fail]:
    def transition[F[_]: Monad](env: Unit, signal: Int)(using
        Ask[F, Globals],
        Stateful[F, Count],
        Raise[F, Fail]
    ): F[Unit] =
      for
        c <- Stateful[F, Count].get
        _ <- Sts.check[F, Fail](c.total + signal <= 100, Fail.Big)
        _ <- Stateful[F, Count].set(Count(c.total + signal, c.seen :+ signal))
      yield ()

  /** Checks its own condition, then runs [[Bump]] on the total of its state (`trans`). */
  object Outer extends Sts[Unit, Count, Int, OuterFail]:
    given Embed[Int, Count, Fail, OuterFail] = Embed(Count.total, OuterFail.Inner(_))

    def transition[F[_]: Monad](env: Unit, signal: Int)(using
        Ask[F, Globals],
        Stateful[F, Count],
        Raise[F, OuterFail]
    ): F[Unit] =
      Sts.check[F, OuterFail](signal >= 0, OuterFail.Negative(signal)) *>
        Sts.trans[F, Unit, Int, Count, Int, Fail, OuterFail](Bump, (), signal)
