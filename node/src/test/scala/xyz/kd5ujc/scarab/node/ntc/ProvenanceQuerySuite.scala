// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.ntc

import xyz.kd5ujc.scarab.protocol.ReferenceBuild
import xyz.kd5ujc.scarab.storage.haskell.state.ReferenceLayout
import cats.effect.IO
import cats.mtl.Ask
import java.nio.file.{Files, Path}
import munit.CatsEffectSuite
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.consensus.state.{
  ConsensusMirrorEnv,
  MirrorClock,
  MirrorProvenance,
  MirrorState,
  Placement
}
import xyz.kd5ujc.scarab.node.{NativeSnapshots, NodeSetup}
import xyz.kd5ujc.scarab.protocol.Era
import xyz.kd5ujc.scarab.storage.{SnapshotCheck, SnapshotV10, StateSnapshot}

class ProvenanceQuerySuite extends CatsEffectSuite:
  private val setup = NodeSetup
    .fromDirectory(ReferenceBuild.Default)(Path.of("fixtures/eras/genesis"))
    .fold(e => fail(e.message), identity)
  private given Ask[IO, ConsensusMirrorEnv] = Ask.const(setup.mirrorEnv())
  private val format = SnapshotV10.format[IO](SnapshotCheck.Full, ReferenceLayout.Release11_1)

  private def queryEnv(observed: Map[Era, BigInt]): QueryEnv = QueryEnv(
    setup.geometry.systemStart,
    setup.geometry,
    setup.praos.forks.copy(triggers = Map.empty),
    setup.mirrorEnv().byron,
    setup.globals.maxSupply,
    setup.genesis.raw,
    setup.mirrorEnv().ledger,
    observed,
    setup.genesis.networkMagic.toLong
  )

  private def view(snapshot: StateSnapshot[MirrorState]): QueryView[MirrorState] =
    val anchor = snapshot.anchor.getOrElse(fail("the retained fixture must have a block anchor"))
    QueryView(anchor.point, Some(anchor.blockNo), snapshot.state)

  private def answer(env: QueryEnv, state: StateSnapshot[MirrorState], query: NtcQuery): Bytes =
    QueryAnswers
      .answerOrWhy(env, view(state), Map.empty, query)
      .fold(e => fail(e.toString), identity)

  test(
    "restored and historical queries use source era starts despite conflicting runtime discoveries"
  ) {
    val original = Files.readAllBytes(Path.of("fixtures/snapshot-v5/conway-native-730.bin"))
    for
      source <- NativeSnapshots
        .decode[IO](original, setup.mirrorEnv())
        .map(_.fold(e => fail(e.message), identity))
      telescope = source.state.ext.ledgerState
      starts = Era.values.toVector
        .zip(telescope.past.map(_.start.epoch) :+ telescope.current.start.epoch)
        .toMap
      recorded = source.copy(state =
        source.state.copy(provenance =
          MirrorProvenance.recorded(source.state.provenance.gaps, starts)
        )
      )
      bytes <- format.encode(recorded).map(_.fold(e => fail(e.toString), identity))
      restored <- format.decode(bytes).map(_.fold(e => fail(e.message), identity))
    yield
      assertEquals(restored.state.provenance.observed, starts)
      val stale = queryEnv(starts.view.mapValues(_ + 100).toMap)
      val empty = queryEnv(Map.empty)
      val acquired = view(restored)
      val placement = Placement(QueryAnswers.geometry(empty, acquired), empty.byron, empty.forks)
      val history = EraHistory.eras(MirrorClock.summary(restored.state, placement))
      // the eras start where the state's telescope says, not where the environment saw them
      assertEquals(history.map(_.start.epoch), Era.values.toVector.flatMap(starts.get))
      val interpreter = NtcQuery.HardFork(HardForkQuery.GetInterpreter)
      val expected = CborOut.build(EraHistory.encode(_, history))
      assertEquals(answer(empty, restored, interpreter), expected)
      assertEquals(answer(stale, restored, interpreter), expected)
      val bound = telescope.current.start
      val eraStart = NtcQuery.EraStart(QueryEra.Conway)
      val expectedStart = CborOut.build(
        _.array(1).array(3).integer(bound.timePs).uint(bound.slot).uint(bound.epoch)
      )
      assertEquals(answer(empty, restored, eraStart), expectedStart)
      assertEquals(answer(stale, restored, eraStart), expectedStart)
      val later = restored.copy(state =
        restored.state.copy(provenance =
          MirrorProvenance.recorded(
            restored.state.provenance.gaps,
            starts.updated(Era.Conway, bound.epoch + 1)
          )
        )
      )
      // The era starts are the telescope's, as the reference's hard-fork state has them: an
      // observation that disagrees with them moves neither answer (#118).
      assertEquals(answer(stale, later, eraStart), expectedStart)
      assertEquals(answer(stale, later, interpreter), expected)
      assertEquals(answer(stale, restored, eraStart), expectedStart)
      assertEquals(answer(stale, restored, interpreter), expected)
  }
