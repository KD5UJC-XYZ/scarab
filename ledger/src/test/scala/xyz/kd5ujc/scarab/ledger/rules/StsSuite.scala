// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.rules

import cats.{Eq, Monad}
import cats.mtl.{Ask, Handle, Raise, Stateful}
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

  type R[A] = RuleT[Fail, Int, A]

  private val globals = Globals(
    LedgerGlobals(BigInt(1000), 100, Rational(1, 20), network = 0),
    EpochGeometry(Instant.EPOCH, 1000, 100, 5, BigDecimal("0.05"))
  )
  private val otherGlobals = globals.copy(ledger = globals.ledger.copy(network = 1))

  // Programs compare by what they do on a sample of environments and states.
  given [A: Eq]: Eq[RuleT[Fail, Int, A]] = Eq.instance((x, y) =>
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
  given [A: Arbitrary]: Arbitrary[RuleT[Fail, Int, A]] = Arbitrary(
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

  checkAll("RuleT Stateful", StatefulTests[R, Int].stateful)
  checkAll(
    "RuleT Stateful, focused through a lens",
    StatefulTests(Stateful[R, Int].focus(Lens[Int, Int](_ + 1)(i => _ => i - 1))).stateful
  )
  checkAll("RuleT Local", LocalTests[R, Globals].local[Int, Int])
  checkAll("RuleT Handle", HandleTests[R, Fail].handle[Int])

  test("tailRecM is stack safe") {
    val loop = Monad[R].tailRecM(0)(n =>
      if n < 1000000 then Monad[R].pure(Left(n + 1)) else Monad[R].pure(Right(n))
    )
    assertEquals(loop.run(globals, 0), Right((0, 1000000)))
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
