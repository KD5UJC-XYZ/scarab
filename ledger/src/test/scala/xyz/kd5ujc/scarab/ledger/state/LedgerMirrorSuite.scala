// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.state

import cats.arrow.FunctionK
import cats.data.Kleisli
import cats.kernel.laws.discipline.EqTests
import cats.laws.discipline.FunctorTests
import cats.mtl.Handle.{handleForApplicativeError, handleKleisli}
import cats.syntax.all.*
import munit.DisciplineSuite
import org.scalacheck.{Arbitrary, Cogen, Gen}
import org.scalacheck.Prop.forAll
import xyz.kd5ujc.scarab.codec.{Bytes, Rational}
import xyz.kd5ujc.scarab.ledger as node

/** The ledger side of the mirror: the laws of the conversion on generated states (a Conway and two
  * pre-Conway eras), and the cases the laws do not reach (what is refused, what is residue).
  */
class LedgerMirrorSuite extends DisciplineSuite:
  type Result[A] = Either[MirrorError, A]
  type M[A] = Kleisli[Result, MirrorEnv, A]

  private val mirror: LedgerMirror[M] = LedgerMirror[M]

  private val run: FunctionK[M, Result] = new FunctionK[M, Result]:
    def apply[A](fa: M[A]): Result[A] = fa.run(FlatStateGen.env)

  private given Arbitrary[WithOrigin[SlotNo]] = Arbitrary(
    Gen.oneOf(
      Gen.const(WithOrigin.Origin),
      Gen.choose(0L, 4200L).map(n => WithOrigin.At(SlotNo.fromUnsigned(n)))
    )
  )

  private val conwayStates = Arbitrary(FlatStateGen.state(conway = true))
  private val alonzoStates = Arbitrary(FlatStateGen.state(conway = false))

  checkAll(
    "LedgerMirror[Conway]",
    MirrorLaws[M, Conway](mirror, run, MirrorLaws.tampers[Conway]).laws(using conwayStates)
  )
  checkAll(
    "LedgerMirror[Alonzo]",
    MirrorLaws[M, Alonzo](mirror, run, MirrorLaws.tampers[Alonzo]).laws(using alonzoStates)
  )
  checkAll(
    "LedgerMirror[Shelley]",
    MirrorLaws[M, Shelley](mirror, run, MirrorLaws.tampers[Shelley]).laws(using alonzoStates)
  )

  // `Mirrored` is a functor with an Eq
  private given Arbitrary[Gap] = Arbitrary(Gen.oneOf(Gap.values.toList))
  private given Arbitrary[Mirrored[Int]] = Arbitrary(
    for
      v <- Arbitrary.arbitrary[Int]
      g <- Gen.containerOf[Set, Gap](Arbitrary.arbitrary[Gap])
    yield Mirrored(v, g)
  )
  private given Cogen[Mirrored[Int]] = Cogen(_.value.toLong)
  checkAll("Eq[Mirrored[Int]]", EqTests[Mirrored[Int]].eqv)
  checkAll("Functor[Mirrored]", FunctorTests[Mirrored].functor[Int, Int, Int])

  private val at: WithOrigin[SlotNo] = WithOrigin.At(SlotNo.fromUnsigned(50L))

  private def toConway(s: node.LedgerState): Result[Mirrored[LedgerImage[Conway]]] =
    run(mirror.to[Conway](at, s))

  private def sampled(conway: Boolean, seed: Long): node.LedgerState =
    FlatStateGen
      .state(conway)
      .pureApply(Gen.Parameters.default.withSize(20), org.scalacheck.rng.Seed(seed))

  private val sample: node.LedgerState = sampled(conway = true, 7L)

  private def shown(e: MirrorError): String = e.show

  private def hash(n: Int, size: Int = 28): Bytes = Bytes.fromArray(Array.fill(size)(n.toByte))

  test("a generated state has no residue and its mirror shows the gaps") {
    val m = toConway(sample).fold(e => fail(e.show), identity)
    assert(m.value.extras.residue.isEmpty)
    assert(m.gaps.contains(Gap.NonMyopic))
  }

  test("the generator reaches the interesting parts of a state") {
    val states = (1L to 300L).map(sampled(conway = true, _))
    val early = (1L to 300L).map(sampled(conway = false, _))
    def count(label: String, ss: Seq[node.LedgerState])(p: node.LedgerState => Boolean): Unit =
      assert(ss.count(p) >= 10, s"$label: ${ss.count(p)} of ${ss.size}")
    count("pools", states)(_.pools.nonEmpty)
    count("a pool delegation", states)(_.accounts.values.exists(_.pool.isDefined))
    count("a mark snapshot with a pool", states)(_.snapshots.mark.pools.nonEmpty)
    count("an idle pool", states)(_.snapshots.mark.idlePools.nonEmpty)
    count("a distribution that is not the set", states)(s => s.poolDistribution != s.snapshots.set)
    count("a reward update", states)(_.rewardUpdate.exists(_.rewards.nonEmpty))
    count("a draft and no update", states)(s => s.rewardDraft.isDefined && s.rewardUpdate.isEmpty)
    count("no completed pulser", states)(_.gov.pulsed.isEmpty)
    count("a future pool", states)(_.futurePools.nonEmpty)
    count("pointers", early)(_.ptrs.nonEmpty)
    count("proposals", early)(_.ppup.current.nonEmpty)
    count("future genesis delegations", early)(_.futureGenDelegs.nonEmpty)
    count("an owner that delegates to its pool", states)(s =>
      s.snapshots.mark.pools.exists((id, p) =>
        p.owners.exists(o => s.snapshots.mark.delegations.get(node.Credential.Key(o)).contains(id))
      )
    )
  }

  // ---- what is refused

  test("a hash of the wrong size is a vocabulary error") {
    val bad = sample.copy(retiring = Map(hash(1, 5) -> BigInt(9)))
    assertEquals(
      toConway(bad).left.toOption.map(_.show),
      shown(MirrorError.Vocab(VocabError.WrongHashLength(VocabError.Kind.KeyHash, 28, 5))).some
    )
  }

  test("a negative amount is a vocabulary error") {
    val bad = sample.copy(treasury = BigInt(-1))
    assertEquals(
      toConway(bad).left.toOption.map(_.show),
      shown(MirrorError.Vocab(VocabError.Negative(VocabError.Kind.Coin, BigInt(-1)))).some
    )
  }

  test("a pool whose reward account is on another network is refused") {
    val pooled = (1L to 200L).map(sampled(conway = true, _)).find(_.pools.nonEmpty).get
    val (id, pool) = pooled.pools.head
    val moved = pool.params.copy(rewardAccount = pool.params.rewardAccount.copy(network = 1))
    val bad = pooled.copy(pools = pooled.pools.updated(id, pool.copy(params = moved)))
    assertEquals(
      toConway(bad).left.toOption.map(_.show),
      shown(MirrorError.NetworkMismatch(0, 1)).some
    )
  }

  test("a snapshot with stake and no delegation is refused") {
    val stake = Map[node.Credential, BigInt](node.Credential.Key(hash(3)) -> BigInt(5))
    val bad = sample.copy(snapshots =
      sample.snapshots.copy(mark = node.Snapshot(stake, Map.empty, Map.empty))
    )
    assertEquals(
      toConway(bad).left.toOption.map(_.show),
      shown(MirrorError.IllFormed(Shape.StakeWithoutDelegation)).some
    )
  }

  // ---- what is residue, and does not get lost

  private val residual: Gen[node.LedgerState] =
    val credential = node.Credential.Key(hash(9))
    FlatStateGen
      .state(conway = false)
      .map(s =>
        s.copy(
          accounts = s.accounts
            .updated(credential, node.Account(1, 2, None, Some(node.DRep.AlwaysAbstain))),
          dreps = Map(credential -> node.DRepState(1, 2)),
          committeeState = Map(credential -> node.CommitteeAuth.Resigned(None)),
          dormantEpochs = 3,
          gov = node.GovState.empty
            .copy(committee = Some(node.Committee(Map(credential -> BigInt(4)), Rational(1, 3))))
        )
      )

  property("fields with no place in the era are residue, and survive") {
    forAll(residual) { s =>
      val image = run(mirror.to[Babbage](at, s))
      val back = image.flatMap(m => run(mirror.from[Babbage](at, m.value)))
      back == Right(LedgerMirror.normalised(s)) &&
      image.map(_.value.extras.residue.isEmpty) == Right(false)
    }
  }

  property("a pointer and a proposal in a Conway state are residue, and survive") {
    forAll(FlatStateGen.state(conway = true)) { s =>
      val ptrs = Map(node.Ptr(7, 0, 0) -> node.Credential.Key(hash(8)))
      val ppup = node.Ppup(Map.empty, Map(hash(1) -> node.ParamUpdate(Map.empty)))
      val odd = s.copy(ptrs = ptrs, ppup = ppup)
      val image = toConway(odd)
      val back = image.flatMap(m => run(mirror.from[Conway](at, m.value)))
      back == Right(LedgerMirror.normalised(odd)) &&
      image.map(_.value.extras.residue.isEmpty) == Right(false)
    }
  }
