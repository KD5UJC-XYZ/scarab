// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.rules

import cats.{Eq, Monad}
import cats.data.{Chain, NonEmptyChain}
import cats.laws.discipline.MonadTests
import cats.mtl.{Ask, Handle, Raise, Stateful}
import cats.mtl.syntax.all.*
import cats.mtl.laws.discipline.TellTests
import cats.syntax.all.*
import java.time.Instant
import munit.DisciplineSuite
import org.scalacheck.{Arbitrary, Gen}
import org.scalacheck.Prop.forAll
import xyz.kd5ujc.scarab.codec.{Bytes, Rational}
import xyz.kd5ujc.scarab.ledger.{
  EpochGeometry,
  LedgerFailure,
  LedgerGlobals,
  MempoolProblem,
  PredicateFailure,
  PreprodDeepTx
}
import xyz.kd5ujc.scarab.ledger.state.VocabError

import RuleT.{Outcome, Policy}

/** The judgment ([[Judge]], its [[RuleT]] interpretation and [[Judgment]]) against the reference's
  * small-steps interpreter (cardano-ledger `f649f975`,
  * `libs/small-steps/src/Control/State/Transition/Extended.hs:668-730`): the order of the reported
  * failures, the failing flag, `whenFailureFree`, the two policies, the certificate recursion of
  * CERTS and DELEGS, and constant stack for deep and long judgments.
  */
class JudgmentSuite extends DisciplineSuite:
  import JudgmentSuite.*

  private val globals = Globals(
    LedgerGlobals(BigInt(1000), 100, Rational(1, 20), network = 0),
    EpochGeometry(Instant.EPOCH, 1000, 100, 5, BigDecimal("0.05"))
  )

  private val judge = Judge[J, Int]

  private def accumulate[A](program: J[A], state: Int = 0): Outcome[Int, Defect, Int, A] =
    program.judge(Policy.Accumulate, globals, state)

  private def firstFailure[A](program: J[A], state: Int = 0): Outcome[Int, Defect, Int, A] =
    program.judge(Policy.FirstFailure, globals, state)

  private def isRefused[A](outcome: Outcome[Int, Defect, Int, A]): Boolean = outcome match
    case Outcome.Refused(_) => true
    case _                  => false

  private def refused(failures: Int*): Outcome[Int, Defect, Int, Nothing] =
    NonEmptyChain
      .fromSeq(failures)
      .fold(fail("a refusal needs a failure"))(Outcome.Refused(_))

  // Programs compare by what they do under both policies, on a sample of states.
  given [A]: Eq[J[A]] = Eq.instance((x, y) =>
    List(0, 3, -7, 100).forall(s =>
      Policy.values.forall(p => x.judge(p, globals, s) == y.judge(p, globals, s))
    )
  )

  // A random program: changes the state, records some failures, maybe in a nest, maybe raises.
  given [A: Arbitrary]: Arbitrary[J[A]] = Arbitrary(
    for
      a <- Arbitrary.arbitrary[A]
      f <- Arbitrary.arbitrary[Int => Int]
      told <- Gen.listOfN(2, Gen.choose(0, 9))
      nested <- Arbitrary.arbitrary[Boolean]
      limit <- Gen.choose(-5, 120)
    yield
      val record = judge.tell(Chain.fromSeq(told))
      Stateful[J, Int].modify(f) *> (if nested then judge.nest(record) else record) *>
        Stateful[J, Int].get.flatMap(s =>
          if s > limit then Raise[J, Defect].raise[Defect, A](Defect.Big) else Monad[J].pure(a)
        )
  )
  given Arbitrary[Chain[Int]] = Arbitrary(Gen.listOf(Gen.choose(0, 9)).map(Chain.fromSeq))

  checkAll("RuleT Monad, judged", MonadTests[J].monad[Int, Int, Int])
  checkAll("RuleT Tell", TellTests[J, Chain[Int]](using judge).tell[Int])

  private val genEvents: Gen[List[Event]] =
    def events(depth: Int): Gen[List[Event]] =
      val leaf = Gen.choose(0, 99).map(Event.Leaf(_))
      val many = Gen.listOfN(3, Gen.choose(0, 99)).map(Event.Many(_))
      val one =
        if depth == 0 then Gen.oneOf(leaf, many)
        else Gen.frequency(3 -> leaf, 1 -> many, 2 -> Gen.lzy(events(depth - 1)).map(Event.Sub(_)))
      Gen.choose(0, 4).flatMap(Gen.listOfN(_, one))
    events(4)

  private def build(events: List[Event]): J[Unit] = events.traverse_ {
    case Event.Leaf(f)  => judge.fail(f)
    case Event.Many(fs) => judge.tell(Chain.fromSeq(fs))
    case Event.Sub(sub) => judge.nest(build(sub))
  }

  test("the failures are reported in the order of the reference's prepending interpreter") {
    forAll(genEvents) { events =>
      assertEquals(
        accumulate(build(events)),
        NonEmptyChain
          .fromSeq(Model.applyRule(events))
          .fold(Outcome.Passed(0, ()))(Outcome.Refused(_))
      )
    }
  }

  test("FirstFailure ends at the chronologically first failure, and refuses what Accumulate does") {
    forAll(genEvents) { events =>
      val program = build(events)
      assertEquals(
        firstFailure(program),
        Model
          .chronological(events)
          .headOption
          .fold(Outcome.Passed(0, ()))(f => Outcome.Refused(NonEmptyChain.one(f)))
      )
      assertEquals(
        isRefused(accumulate(program)),
        isRefused(firstFailure(program))
      )
    }
  }

  test("several failures told at once are those failures recorded one after the other") {
    forAll(Gen.listOf(Gen.choose(0, 9))) { fs =>
      val told = judge.tell(Chain.fromSeq(fs))
      val one = fs.traverse_(judge.fail)
      assertEquals(accumulate(told), accumulate(one))
      assertEquals(accumulate(judge.nest(told)), accumulate(judge.nest(one)))
    }
  }

  test("a rule's own failures are reversed at the root, a nest's one level down are not") {
    assertEquals(accumulate(judge.fail(1) *> judge.fail(2)), refused(2, 1))
    assertEquals(accumulate(judge.nest(judge.fail(1) *> judge.fail(2))), refused(1, 2))
    assertEquals(
      accumulate(judge.fail(1) *> judge.nest(judge.fail(2) *> judge.fail(3)) *> judge.fail(4)),
      refused(4, 2, 3, 1)
    )
    assertEquals(
      accumulate(judge.nest(judge.nest(judge.fail(1) *> judge.fail(2)))),
      refused(2, 1)
    )
  }

  test("a nest that records nothing adds nothing") {
    assertEquals(accumulate(judge.nest(Monad[J].unit)), Outcome.Passed(0, ()))
    assertEquals(
      accumulate(judge.nest(judge.nest(judge.tell(Chain.nil))) *> judge.fail(1)),
      refused(1)
    )
  }

  test("the rule continues after a failure, and the state it computes is threaded on") {
    val program = for
      _ <- judge.fail(1)
      _ <- Stateful[J, Int].set(5)
      s <- Stateful[J, Int].get
      _ <- judge.fail(s)
    yield s
    assertEquals(accumulate(program), refused(5, 1))
    assertEquals(accumulate(Stateful[J, Int].set(5) *> judge.failing), Outcome.Passed(5, false))
  }

  test("the failing flag: anything recorded so far anywhere in the judgment") {
    val flag = judge.failing
    assertEquals(accumulate(flag), Outcome.Passed(0, false))
    assertEquals(accumulate(judge.nest(flag)), Outcome.Passed(0, false))
    // a later sibling after a failure
    assertEquals(
      accumulate(judge.fail(1) *> flag.flatMap(f => judge.fail(if f then 10 else 20))),
      refused(10, 1)
    )
    // the parent after a child failed
    assertEquals(
      accumulate(judge.nest(judge.fail(1)) *> flag.flatMap(f => judge.fail(if f then 10 else 20))),
      refused(10, 1)
    )
    // a child sees the parent's earlier failure
    assertEquals(
      accumulate(judge.fail(1) *> judge.nest(flag.flatMap(f => judge.fail(if f then 10 else 20)))),
      refused(10, 1)
    )
    // a later sub-rule sees an earlier sub-rule's failure
    assertEquals(
      accumulate(
        judge.nest(judge.fail(1)) *> judge.nest(flag.flatMap(f => judge.fail(if f then 10 else 20)))
      ),
      refused(10, 1)
    )
    // under FirstFailure a failure ends the judgment, so the flag is never seen set
    assertEquals(firstFailure(flag), Outcome.Passed(0, false))
  }

  test("whenFailureFree, whenFailureFreeDefault and ifFailureFree skip once anything failed") {
    val guarded = Sts.whenFailureFree[J, Int](judge.fail(2))
    assertEquals(accumulate(guarded), refused(2))
    assertEquals(accumulate(judge.fail(1) *> guarded), refused(1))
    assertEquals(accumulate(judge.nest(judge.fail(1)) *> judge.nest(guarded)), refused(1))
    val valued = Sts.whenFailureFreeDefault[J, Int, Int](0)(Stateful[J, Int].get)
    assertEquals(accumulate(valued, 7), Outcome.Passed(7, 7))
    assertEquals(accumulate(judge.fail(1) *> valued *> judge.fail(9), 7), refused(9, 1))
    val branch =
      Sts.ifFailureFree[J, Int, Int](Monad[J].pure(1), Monad[J].pure(2)).flatMap(judge.fail)
    assertEquals(accumulate(branch), refused(1))
    assertEquals(accumulate(judge.fail(5) *> branch), refused(2, 5))
  }

  test("a defect raised after a recorded failure ends the judgment with the defect") {
    val program = judge.fail(1) *> Raise[J, Defect].raise[Defect, Unit](Defect.Big) *> judge.fail(2)
    assertEquals(accumulate(program), Outcome.Raised(Defect.Big))
    assertEquals(
      Judgment.judge(program, Policy.Accumulate)(globals, 0),
      Left(Defect.Big)
    )
    assertEquals(
      Judgment.judge(judge.fail(1), Policy.Accumulate)(globals, 0),
      Right(Left(NonEmptyChain.one(1)))
    )
  }

  test(
    "a recovery resumes from the state, the failures and the flag the protected program began with"
  ) {
    val protectedProgram =
      Stateful[J, Int].set(99) *> judge.fail(1) *> Raise[J, Defect].raise[Defect, Unit](Defect.Big)
    val program =
      Handle[J, Defect].handleWith(protectedProgram)(_ => Monad[J].unit) *> judge.failing
    assertEquals(accumulate(program, 7), Outcome.Passed(7, false))
    val kept = judge.fail(3) *> program
    assertEquals(accumulate(kept), refused(3))
  }

  test("CERTS and DELEGS recurse on the prefix: the zigzag order of the reference") {
    // Babbage: LEDGER is the root, DELEGS one level down with no failure in its base case.
    assertEquals(babbage(1), Some(List(1)))
    assertEquals(babbage(2), Some(List(1, 2)))
    assertEquals(babbage(3), Some(List(2, 1, 3)))
    assertEquals(babbage(4), Some(List(3, 1, 2, 4)))
    // Conway: MEMPOOL is the root, LEDGER below it, CERTS below that with a failing base case.
    assertEquals(conway(1), Some(List(Cert.C(1), Cert.Base)))
    assertEquals(conway(2), Some(List(Cert.C(2), Cert.Base, Cert.C(1))))
    assertEquals(conway(3), Some(List(Cert.C(3), Cert.C(1), Cert.Base, Cert.C(2))))
    assertEquals(conway(4), Some(List(Cert.C(4), Cert.C(2), Cert.Base, Cert.C(1), Cert.C(3))))
    (1 to 8).foreach { n =>
      assertEquals(babbage(n), Some(Model.applyRule(List(Model.certs(n, base = false)))))
      assertEquals(
        conway(n),
        Some(Model.applyRule(List(Event.Sub(List(Model.certs(n, base = true))))).map(Cert.of))
      )
    }
  }

  private def certs[Rec](n: Int, base: JR[Rec, Unit], cert: Int => JR[Rec, Unit]): JR[Rec, Unit] =
    val j = Judge[[A] =>> JR[Rec, A], Rec]
    (1 to n).foldLeft(base)((prefix, c) => j.nest(prefix) *> j.nest(cert(c)))

  private def refusalOf[Rec](program: JR[Rec, Unit]): Option[List[Rec]] =
    Judgment
      .judge(program, Policy.Accumulate)(globals, 0)
      .toOption
      .flatMap(_.swap.toOption)
      .map(_.toList)

  private def babbage(n: Int): Option[List[Int]] =
    refusalOf(judge.nest(certs[Int](n, Monad[J].unit, judge.fail)))

  private def conway(n: Int): Option[List[Cert]] =
    val j = Judge[[A] =>> JR[Cert, A], Cert]
    val ledger = j.nest(certs[Cert](n, j.fail(Cert.Base), c => j.fail(Cert.C(c))))
    refusalOf(j.nest(ledger))

  test(
    "a rule that raises its refusal reports it as the predicate failures, a defect as a defect"
  ) {
    val spent = PredicateFailure.Mempool(MempoolProblem.AllInputsSpent)
    val vocab = LedgerFailure.Vocab(VocabError.Zero(VocabError.Kind.Coin))
    Policy.values.foreach { policy =>
      def run(raised: Option[LedgerFailure]) =
        Judgment.run(Raising, policy)(globals, (), 4, raised)
      assertEquals(run(None), Right(Right(5)))
      assertEquals(
        run(Some(LedgerFailure.Rejected(Bytes.empty, spent))),
        Right(Left(NonEmptyChain.one(spent)))
      )
      assertEquals(run(Some(vocab)), Left(vocab))
    }
  }

  test("long and deep judgments run in constant stack, and so does their order") {
    val n = 100000
    val sequence = (1 to n).toList.traverse_(judge.fail)
    val deep = (1 to n).foldLeft(judge.fail(0))((program, _) => judge.nest(program))
    val zigzag = judge.nest(certs[Int](n, Monad[J].unit, judge.fail))
    // Only sizes and a few elements are compared: a deep trace must not be printed or compared.
    val (long, nested, recursion, first) = PreprodDeepTx.onSmallStack(
      (
        refusalOf(sequence).map(fs => (fs.size, fs.headOption, fs.lastOption)),
        refusalOf(deep),
        refusalOf(zigzag).map(fs => (fs.size, fs.sorted == (1 to n).toList, fs.lastOption)),
        firstFailure(zigzag)
      )
    )
    assertEquals(long, Some((n, Some(n), Some(1))))
    assertEquals(nested, Some(List(0)))
    assertEquals(recursion, Some((n, true, Some(n))))
    assertEquals(first, Outcome.Refused(NonEmptyChain.one(1)))
  }

object JudgmentSuite:
  /** Adds one to its state, then raises the failure of its signal, if any. */
  object Raising extends Sts[Unit, Int, Option[LedgerFailure], LedgerFailure]:
    def transition[F[_]: Monad](env: Unit, signal: Option[LedgerFailure])(using
        Ask[F, Globals],
        Stateful[F, Int],
        Raise[F, LedgerFailure]
    ): F[Unit] =
      Stateful[F, Int].modify(_ + 1) *> signal.fold(Monad[F].unit)(_.raise[F, Unit])

  /** The defect the programs raise. */
  enum Defect:
    case Big

  /** A judged program recording `Rec` and changing an `Int`. */
  type JR[Rec, A] = RuleT[Rec, Defect, Int, A]

  type J[A] = JR[Int, A]

  /** A rule's events, in the order it runs them: a predicate failure, a predicate with several
    * failures (`runTest`), or a sub-rule (`trans`).
    */
  enum Event:
    case Leaf(failure: Int)
    case Many(failures: List[Int])
    case Sub(events: List[Event])

  /** The failures of the certificate recursion: a certificate's, or the base case's. */
  enum Cert:
    case C(n: Int)
    case Base

  object Cert:
    /** The model's encoding of the base case is 0. */
    def of(n: Int): Cert = if n == 0 then Base else C(n)

  /** A literal port of the reference's interpreter (`Extended.hs:703-722`) as the oracle: each run
    * of a rule keeps its own list and prepends to it, a predicate's errors as `reverse errs ++ acc`
    * (708), a sub-rule's list one element at a time (`traverse_ (\a -> modify (first (a :)))`,
    * 720-722). The root's list is the reported one (`applySTS`). It recurses on the depth of the
    * generated tree, which is small.
    */
  object Model:
    def applyRule(events: List[Event]): List[Int] =
      var acc = List.empty[Int]
      events.foreach {
        case Event.Leaf(f)  => acc = f :: acc
        case Event.Many(fs) => acc = fs.reverse ++ acc
        case Event.Sub(sub) => applyRule(sub).foreach(a => acc = a :: acc)
      }
      acc

    /** The failures in the order they were recorded. */
    def chronological(events: List[Event]): List[Int] = events.flatMap {
      case Event.Leaf(f)  => List(f)
      case Event.Many(fs) => fs
      case Event.Sub(sub) => chronological(sub)
    }

    /** CERTS of `n` certificates as a sub-rule: `gamma :|> c -> trans @CERTS gamma; trans @CERT c`
      * (Conway `Certs.hs:242-246`, Shelley `Delegs.hs:190-198`), certificate `c` failing with `c`
      * and the base case with 0 when `base` holds.
      */
    def certs(n: Int, base: Boolean): Event =
      if n == 0 then Event.Sub(if base then List(Event.Leaf(0)) else Nil)
      else Event.Sub(List(certs(n - 1, base), Event.Sub(List(Event.Leaf(n)))))
