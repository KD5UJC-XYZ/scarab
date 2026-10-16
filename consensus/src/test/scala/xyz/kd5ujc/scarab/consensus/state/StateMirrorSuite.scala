// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.consensus.state

import cats.arrow.FunctionK
import cats.data.Kleisli
import cats.mtl.Handle.{handleForApplicativeError, handleKleisli}
import cats.syntax.all.*
import munit.DisciplineSuite
import org.scalacheck.Gen
import org.scalacheck.rng.Seed
import xyz.kd5ujc.scarab.ledger.Nonce
import xyz.kd5ujc.scarab.ledger.state.{Sorted, WithOrigin}
import xyz.kd5ujc.scarab.protocol.Era

/** The consensus side of the mirror: the laws of the whole conversion on generated states of the
  * three protocols (PBFT in Byron, TPraos from Shelley to Alonzo, Praos from Babbage), and the
  * cases the laws do not reach.
  */
class StateMirrorSuite extends DisciplineSuite:
  type Result[A] = Either[StateMirrorError, A]
  type M[A] = Kleisli[Result, ConsensusMirrorEnv, A]

  private val env = NodeStateGen.env
  private val mirror: StateMirror[M] = StateMirror[M]

  private val run: FunctionK[M, Result] = new FunctionK[M, Result]:
    def apply[A](fa: M[A]): Result[A] = fa.run(env)

  private val laws = StateMirrorLaws[M](mirror, run, env)

  checkAll(
    "StateMirror[Byron]",
    laws.laws(using org.scalacheck.Arbitrary(NodeStateGen.inByron))
  )
  checkAll(
    "StateMirror[TPraos eras]",
    laws.laws(using org.scalacheck.Arbitrary(NodeStateGen.inTPraosEras))
  )
  checkAll(
    "StateMirror[Praos eras]",
    laws.laws(using org.scalacheck.Arbitrary(NodeStateGen.inPraosEras))
  )

  private def sample(gen: Gen[Located], n: Int): List[Located] =
    (1 to n).toList.map(i =>
      gen.pureApply(org.scalacheck.Gen.Parameters.default.withSize(20), Seed(i.toLong))
    )

  private def mirrored(l: Located): MirrorState =
    run(mirror.toMirror(l.anchor, l.state)).fold(e => fail(e.show), _.value)

  test("the era of a generated state is the era it was generated for") {
    Era.values.toList.foreach { era =>
      sample(NodeStateGen.of(era), 20).foreach { l =>
        assertEquals(StateMirror.eraOf(env, l.state), era)
        assertEquals(mirrored(l).ext.ledgerState.era, era)
        assertEquals(mirrored(l).ext.headerState.chainDep.era, era)
      }
    }
  }

  test("every change to a mirror is applied to some mirror, in every protocol") {
    val applied = List(NodeStateGen.inByron, NodeStateGen.inTPraosEras, NodeStateGen.inPraosEras)
      .map { gen =>
        sample(gen, 150).flatMap(l => StateTampers.all(l.state, mirrored(l)).map(_.name)).toSet
      }
    val structural = Set(
      "the telescope bounds, later by a slot",
      "the annotation of the header tip",
      "the last slot of the chain-dependent state",
      "the tip of the ledger state",
      "the PBFT count of a genesis key",
      "the header telescope in another era",
      "extras of the other kind of state"
    )
    val seen = applied.reduce(_ ++ _)
    assertEquals(structural -- seen, Set.empty[String])
    // the protocols each see their own: PBFT counts in Byron, last slots after it
    assert(applied.head.contains("the PBFT count of a genesis key"))
    assert(applied(2).contains("the last slot of the chain-dependent state"))
    assert(seen.exists(_.startsWith("the ledger side:")))
  }

  test("the ledger side's refusals come through, wrapped") {
    val l = sample(NodeStateGen.inPraosEras, 1).head
    val ledgerTampers =
      StateTampers.all(l.state, mirrored(l)).filter(_.name.startsWith("the ledger side"))
    assert(ledgerTampers.nonEmpty)
    ledgerTampers.foreach(t => assertEquals(run(mirror.fromMirror(t.state)), Left(t.expected)))
  }

  test("Byron has no gaps, a Shelley-based era has the transition") {
    val byron = sample(NodeStateGen.inByron, 3).head
    assertEquals(run(mirror.toMirror(byron.anchor, byron.state)).map(_.gaps), Right(Set.empty))
    val shelley = sample(NodeStateGen.inPraosEras, 3).head
    assert(
      run(mirror.toMirror(shelley.anchor, shelley.state))
        .exists(_.gaps.contains(xyz.kd5ujc.scarab.ledger.state.Gap.ShelleyTransition))
    )
  }

  test("a block with a Byron boundary flag after the chain left Byron is refused") {
    val l = sample(NodeStateGen.inPraosEras, 1).head
    val boundary = l.anchor.map(_.copy(boundary = true))
    assert(boundary.isDefined)
    assertEquals(
      run(mirror.toMirror(boundary, l.state)).left.toOption,
      Some(StateMirrorError.BoundaryOutsideByron(StateMirror.eraOf(env, l.state)))
    )
  }

  test("a Byron boundary block comes back as one") {
    val l = sample(NodeStateGen.inByron, 5)
      .flatMap(l => l.anchor.map(a => l.copy(anchor = Some(a.copy(boundary = true)))))
      .head
    val back = run(mirror.toMirror(l.anchor, l.state).flatMap(m => mirror.fromMirror(m.value)))
    assertEquals(back.map(_._1), Right(l.anchor))
  }

  test("a state whose chain-dependent state is of another protocol than its era's is refused") {
    val l = sample(NodeStateGen.inPraosEras, 1).head
    val era = StateMirror.eraOf(env, l.state)
    val tpraos = ChainDepState.TPraos(
      TPraosState(
        WithOrigin.Origin,
        TPraosLedgerState(
          PrtclState(Sorted.empty, Nonce.Neutral, Nonce.Neutral),
          TicknState(Nonce.Neutral, Nonce.Neutral),
          Nonce.Neutral
        )
      )
    )
    val bad = l.state.copy(header = HeaderState.live(tpraos))
    assertEquals(
      run(mirror.toMirror(l.anchor, bad)).left.toOption,
      Some(StateMirrorError.ChainDepFamily(era))
    )
  }
