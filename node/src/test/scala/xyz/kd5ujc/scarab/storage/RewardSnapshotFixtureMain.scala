// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.mtl.Ask
import io.circe.parser
import java.nio.file.{Files, Path}
import munit.Assertions
import scala.jdk.CollectionConverters.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.consensus.BlockRef
import xyz.kd5ujc.scarab.consensus.state.{ConsensusMirrorEnv, EraLedgerState}
import xyz.kd5ujc.scarab.ledger.Block
import xyz.kd5ujc.scarab.ledger.state.{ProtVer, PulsingRewUpdate, ShelleyBasedEra}
import xyz.kd5ujc.scarab.node.{MirrorSteps, NodeSetup}
import xyz.kd5ujc.scarab.storage.haskell.state.{HaskellState, HaskellStateCodecs, Versions}

/** Fixture producer also compiled against pre-change main to prove the old opaque writer's V5
  * bytes.
  */
object RewardSnapshotFixtureMain extends Assertions:
  enum Mode:
    case Create, Rewrite

  def main(args: Array[String]): Unit =
    val mode = args(0) match
      case "create"  => Mode.Create
      case "rewrite" => Mode.Rewrite
      case _         => fail("expected create or rewrite")
    val setup = NodeSetup.fromDirectory(Path.of(args(1))).fold(e => fail(e.toString), identity)
    given Ask[IO, ConsensusMirrorEnv] = Ask.const(setup.mirrorEnv())
    val format = SnapshotV5.format[IO](SnapshotCheck.Full)
    val snapshot = mode match
      case Mode.Rewrite =>
        format
          .decode(Files.readAllBytes(Path.of(args(2))))
          .unsafeRunSync()
          .fold(e => fail(e.toString), identity)
      case Mode.Create =>
        val blocks = Files
          .readAllLines(Path.of(args(2)))
          .asScala
          .filter(_.nonEmpty)
          .map(text => Bytes.fromHex(text).fold(e => fail(e.toString), identity))
        val before = blocks.foldLeft(StateSnapshot(None, setup.mirrorStart)) { (previous, raw) =>
          val block =
            Block.decode(raw, setup.geometry.byron.epochSlots).fold(e => fail(e.toString), identity)
          val state = MirrorSteps
            .block(setup, previous.state, block.header, raw)
            .fold(e => fail(e.toString), identity)
          StateSnapshot(
            Some(
              BlockRef(
                block.header.slot,
                block.header.hash,
                block.header.blockNo,
                block.header.isBoundary
              )
            ),
            state
          )
        }
        val json = parser
          .parse(Files.readString(Path.of(args(3))))
          .fold(e => fail(e.toString), identity)
          .as[Vector[io.circe.Json]]
          .fold(e => fail(e.toString), identity)
        val input = json
          .find(_.hcursor.get[String]("name").contains("conway-v10"))
          .getOrElse(fail("no Conway case"))
        val raw = Bytes
          .fromHex(input.hcursor.get[String]("initial").fold(e => fail(e.toString), identity))
          .fold(e => fail(e.toString), identity)
        given ProtVer = Versions.eraLow(ShelleyBasedEra.SConway)
        val pending: PulsingRewUpdate = HaskellStateCodecs.pulsingRewUpdate
          .decodeBytes(raw, HaskellState.limits)
          .fold(e => fail(e.toString), identity)
        val next = before.state.ext.ledgerState.state match
          case i: EraLedgerState.InShelleyEra[e] =>
            EraLedgerState.InShelleyEra(
              i.era,
              i.state.copy(nes = i.state.nes.copy(ru = Some(pending)))
            )
          case _ => fail("fixture did not reach a Shelley-based era")
        before.copy(state =
          before.state.copy(ext =
            before.state.ext.copy(ledgerState = before.state.ext.ledgerState.withState(next))
          )
        )
    val written = format.encode(snapshot).unsafeRunSync().fold(e => fail(e.toString), identity)
    val out = args.last
    Files.write(Path.of(out), written)
    println(s"V5 pending snapshot: ${written.length} bytes -> $out")
