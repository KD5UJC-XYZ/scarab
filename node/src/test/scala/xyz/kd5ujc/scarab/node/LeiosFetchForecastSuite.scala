// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.consensus.{Anchor, BlockRef}
import xyz.kd5ujc.scarab.consensus.leios.BytesSize
import xyz.kd5ujc.scarab.consensus.state.MirrorState
import xyz.kd5ujc.scarab.ledger.Block
import xyz.kd5ujc.scarab.network.FailsOnUncaught
import xyz.kd5ujc.scarab.node.read.ChainRead.given
import xyz.kd5ujc.scarab.protocol.ReferenceBuild
import xyz.kd5ujc.scarab.protocol.LeiosWord.Word64

/** The closure's bound the Leios build's fetch decision reads from the immutable tip each pass
  * (`forecastMaxEbTxsSize`, `NodeKernel.hs:586-593` at ouroboros-consensus 2416493b), on the
  * reference cluster's chain of `fixtures/dijkstra-devnet`, which forks into Dijkstra at slot 200
  * (provenance in its README).
  */
class LeiosFetchForecastSuite extends FailsOnUncaught:
  private val devnet = Path.of("fixtures", "dijkstra-devnet")
  private val setup = NodeSetup
    .fromConfigFile(ReferenceBuild.LeiosB004fe0c)(devnet.resolve("genesis/config.json"))
    .fold(e => fail(e.message), identity)
  private val praos = setup.praos.copy(forks = setup.praos.forks.copy(maxMajorProtVer = 12))
  private def hex(s: String): Bytes = Bytes.fromHex(s).fold(e => fail(e.message), identity)

  private val conwayBlocks = Files
    .readAllLines(devnet.resolve("blocks.tsv"))
    .asScala
    .toVector
    .filter(_.nonEmpty)
    .map(_.split('\t'))
    .filter(_(0) == "7")
    .map { r =>
      val raw = hex(r(4))
      (Block.decode(raw, setup.geometry.byron.epochSlots).fold(e => fail(e.message), identity), raw)
    }

  /** The state after the last Conway block, slot 199, and its tip. */
  private lazy val immutable: Anchor[MirrorState] =
    val state = conwayBlocks.foldLeft(setup.mirrorStart) { case (state, (block, raw)) =>
      MirrorSteps
        .block(setup, state, block.header, raw, Some(praos))
        .fold(f => fail(s"block ${block.header.blockNo} refused: ${f.reason}"), identity)
    }
    val last = conwayBlocks.last._1.header
    Anchor(Some(BlockRef(last.slot, last.hash, last.blockNo)), state)

  private def rules(build: ReferenceBuild) =
    given HeaderTimeRead[MirrorState] = HeaderTimeRead.mirror(setup.byronHistory)
    NodeHeaderRules[MirrorState](praos, setup.globals.copy(referenceBuild = build))

  private def forecast(slot: Long, build: ReferenceBuild = ReferenceBuild.LeiosB004fe0c) =
    LeiosFetchNode.forecast(rules(build), immutable, Word64.clamped(slot))

  test("a Dijkstra slot's bound is the Dijkstra genesis's maxEndorserBlockTxsSize") {
    assertEquals(forecast(202).map(_.toLong), Right(260000L))
  }

  test("a slot still in Conway has a view without the bound, which answers 0") {
    assertEquals(forecast(199).map(_.toLong), Right(0L))
    assertEquals(forecast(150).map(_.toLong), Right(0L))
  }

  test("a slot past the forecast's range has no bound yet") {
    forecast(1000000) match
      case Left(LeiosFetchNode.Unforecast.View(Praos2Headers.Unforecast.Horizon)) => ()
      case other => fail(s"expected the horizon, got $other")
  }

  test("a build without Praos2 forecasts no Leios view") {
    forecast(202, ReferenceBuild.Release11_2) match
      case Left(LeiosFetchNode.Unforecast.NoPraos2) => ()
      case other                                    => fail(s"expected no Praos2, got $other")
  }

  test("the forecast's bound is a byte size as the fetch logic reads it") {
    assertEquals(forecast(202).map(identity[BytesSize]), Right(BytesSize.wrap(260000L)))
  }
