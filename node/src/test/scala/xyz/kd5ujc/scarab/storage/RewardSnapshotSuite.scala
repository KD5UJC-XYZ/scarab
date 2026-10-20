// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage

import cats.effect.IO
import cats.mtl.Ask
import java.nio.file.Path
import munit.CatsEffectSuite
import xyz.kd5ujc.scarab.consensus.state.{
  ConsensusMirrorEnv,
  EraLedgerState,
  MirrorState,
  MirrorStep
}
import xyz.kd5ujc.scarab.ledger.rules.Rewards
import xyz.kd5ujc.scarab.ledger.state.{PulsingRewUpdate, ProtVer, ShelleyBasedEra}
import xyz.kd5ujc.scarab.node.NodeSetup
import xyz.kd5ujc.scarab.storage.haskell.state.{HaskellStateCodecs, RewardTimingCases, Versions}

class RewardSnapshotSuite extends CatsEffectSuite:
  private val setup =
    NodeSetup.fromDirectory(Path.of("fixtures/eras/genesis")).fold(e => fail(e.toString), identity)
  private given Ask[IO, ConsensusMirrorEnv] = Ask.const(setup.mirrorEnv())
  private val format = SnapshotV5.format[IO](SnapshotCheck.Full)
  private given ProtVer = Versions.eraLow(ShelleyBasedEra.SConway)

  private def pending(state: MirrorState): PulsingRewUpdate =
    MirrorStep
      .nesOf(state)
      .flatMap { case n: xyz.kd5ujc.scarab.ledger.rules.EraNes.InEra[e] =>
        n.nes.ru
      }
      .getOrElse(fail("snapshot has no captured reward prefix"))

  private def replace(state: MirrorState, update: PulsingRewUpdate): MirrorState =
    val next = state.ext.ledgerState.state match
      case i: EraLedgerState.InShelleyEra[e] =>
        EraLedgerState.InShelleyEra(i.era, i.state.copy(nes = i.state.nes.copy(ru = Some(update))))
      case _ => fail("expected Shelley-based era")
    state.copy(ext = state.ext.copy(ledgerState = state.ext.ledgerState.withState(next)))

  private def encoded(update: PulsingRewUpdate) =
    HaskellStateCodecs.pulsingRewUpdate.write(update).fold(e => fail(e.toString), identity)

  test("pre-change opaque V5 bytes load without migration and retain imported pending rewards") {
    val stream = Option(getClass.getClassLoader.getResourceAsStream("rewards-pending-v5.bin"))
      .getOrElse(fail("missing old V5 fixture"))
    val old =
      try stream.readAllBytes()
      finally stream.close()
    for
      restored <- format.decode(old).map(_.fold(e => fail(e.toString), identity))
      initial = pending(restored.state)
      first = Rewards.pulse(initial).fold(e => fail(e.toString), identity)
      checkpoint = restored.copy(state = replace(restored.state, first))
      written <- format.encode(checkpoint).map(_.fold(e => fail(e.toString), identity))
      restarted <- format.decode(written).map(_.fold(e => fail(e.toString), identity))
      unchanged <- format.encode(restored).map(_.fold(e => fail(e.toString), identity))
    yield
      assert(java.util.Arrays.equals(old, unchanged), "existing V5 wire bytes changed")
      assertEquals(encoded(pending(restarted.state)), encoded(first))
      assertEquals(
        Rewards.pulse(pending(restarted.state)).map(encoded),
        Rewards.pulse(first).map(encoded)
      )
      assertEquals(Rewards.complete(pending(restarted.state)), Rewards.complete(initial))
      // Rollback to the retained immutable pre-pulse snapshot reproduces the original prefix.
      assertEquals(Rewards.pulse(pending(restored.state)).map(encoded), Right(encoded(first)))
  }

  test(
    "native V5 restart completes independently observed Haskell prefixes with exact accounting"
  ) {
    import RewardTimingCases.{complete, field, load, state}
    val stream = Option(getClass.getClassLoader.getResourceAsStream("rewards-pending-v5.bin"))
      .getOrElse(fail("missing V5 fixture"))
    val bytes =
      try stream.readAllBytes()
      finally stream.close()
    val rows = load("results.json").filter { row =>
      field[String](row, "name") == "conway-v10/dense"
    }
    assertEquals(rows.size, 1)
    val row = rows.head
    val wanted = complete(row)
    val prefixes = (Vector(state(field[String](row, "initial"))) ++
      field[Vector[io.circe.Json]](row, "timing")
        .flatMap(r => field[Option[String]](r, "state").map(state))).collect {
      case p: PulsingRewUpdate.Pulsing => p
    }.distinct
    assert(prefixes.size > 2, "restart controls must include partially consumed captured inputs")
    for
      snapshot <- format.decode(bytes).map(_.fold(e => fail(e.toString), identity))
      _ <- prefixes.foldLeft(IO.unit) { (previous, prefix) =>
        previous *> (for
          written <- format
            .encode(snapshot.copy(state = replace(snapshot.state, prefix)))
            .map(_.fold(e => fail(e.toString), identity))
          restarted <- format.decode(written).map(_.fold(e => fail(e.toString), identity))
        yield
          assertEquals(encoded(pending(restarted.state)), encoded(prefix))
          assertEquals(Rewards.complete(pending(restarted.state)), Right(wanted)))
      }
    yield ()
  }
