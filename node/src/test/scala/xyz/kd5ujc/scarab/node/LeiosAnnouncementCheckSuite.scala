// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.consensus.{Anchor, BlockRef, Praos2}
import xyz.kd5ujc.scarab.consensus.state.MirrorState
import xyz.kd5ujc.scarab.ledger.{AnnouncementInvalidity, Block, EnvelopeError, LeiosHeader}
import xyz.kd5ujc.scarab.network.FailsOnUncaught
import xyz.kd5ujc.scarab.node.read.ChainRead.given
import xyz.kd5ujc.scarab.protocol.{Era, ReferenceBuild}

/** The out-of-context check of a header relayed as a LeiosNotify announcement against the immutable
  * tip ([[NodeHeaderRules.announcementRules]], [[Praos2Headers.announcement]]) against
  * `validateAnnouncementHeader` (`LeiosDemoLogic/Announcements/Validate.hs:205-247` at
  * ouroboros-consensus 2416493b), on the reference cluster's chain of `fixtures/dijkstra-devnet`,
  * which forks into Dijkstra at slot 200 (provenance in its README): the immutable tip is the state
  * after the last Conway block, slot 199, and the headers are the cluster's Dijkstra headers.
  */
class LeiosAnnouncementCheckSuite extends FailsOnUncaught:
  private val devnet = Path.of("fixtures", "dijkstra-devnet")
  private val setup = NodeSetup
    .fromConfigFile(ReferenceBuild.LeiosB004fe0c)(devnet.resolve("genesis/config.json"))
    .fold(e => fail(e.message), identity)
  private val praos = setup.praos.copy(forks = setup.praos.forks.copy(maxMajorProtVer = 12))
  private def hex(s: String): Bytes = Bytes.fromHex(s).fold(e => fail(e.message), identity)

  private val blocks = Files
    .readAllLines(devnet.resolve("blocks.tsv"))
    .asScala
    .toVector
    .filter(l => l.nonEmpty && !l.startsWith("tag"))
    .map(_.split('\t'))
    .map { r =>
      val raw = hex(r(4))
      (
        r(0),
        Block.decode(raw, setup.geometry.byron.epochSlots).fold(e => fail(e.message), identity),
        raw
      )
    }

  /** The state after the last Conway block, slot 199, and its tip. */
  private lazy val immutable: Anchor[MirrorState] =
    val conway = blocks.filter(_._1 == "7")
    val state = conway.foldLeft(setup.mirrorStart) { case (state, (_, block, raw)) =>
      MirrorSteps
        .block(setup, state, block.header, raw, Some(praos))
        .fold(f => fail(s"block ${block.header.blockNo} refused: ${f.reason}"), identity)
    }
    val last = conway.last._2.header
    Anchor(Some(BlockRef(last.slot, last.hash, last.blockNo)), state)

  private val dijkstra: Vector[LeiosHeader] = blocks
    .filter(_._1 == "8")
    .map(b => LeiosChainSync.leiosHeader(b._2.header).getOrElse(fail("not a Leios header")))

  private def rules(build: ReferenceBuild = ReferenceBuild.LeiosB004fe0c) =
    given HeaderTimeRead[MirrorState] = HeaderTimeRead.mirror(setup.byronHistory)
    NodeHeaderRules[MirrorState](praos, setup.globals.copy(referenceBuild = build))

  private def check(header: LeiosHeader): AnnouncementCheck =
    rules().announcementRules
      .getOrElse(fail("the Leios build checks announcements"))(
        immutable.state,
        immutable.block,
        header
      )

  private val fresh = AnnouncementCheck.Checked(Right(Praos2.OcinStaleness.Fresh))

  test("a Dijkstra header after the tip is fresh, the view forecast across the boundary") {
    assertEquals(dijkstra.head.slot, BigInt(202))
    assertEquals(check(dijkstra.head), fresh)
  }

  test("a header that does not extend the tip is checked out of context, and fresh") {
    // its predecessor, the header of slot 202, is not the tip's
    assertEquals(check(dijkstra(1)), fresh)
  }

  test("a slot below the immutable tip is refused") {
    assertEquals(
      check(dijkstra.head.copy(slot = BigInt(150))),
      AnnouncementCheck.Checked(Left(AnnouncementInvalidity.SlotBeforeImmutableTip))
    )
  }

  test("a slot past the forecast's range is refused, not left for later") {
    assertEquals(
      check(dijkstra.head.copy(slot = BigInt(1000000))),
      AnnouncementCheck.Checked(Left(AnnouncementInvalidity.OutsideHorizon))
    )
  }

  test("a slot whose view is Conway's is of the wrong era, before any protocol check") {
    check(dijkstra.head.copy(slot = BigInt(199))) match
      case AnnouncementCheck.Checked(
            Left(AnnouncementInvalidity.EnvelopeInvalid(EnvelopeError.WrongEra(era, viewEra, _, _)))
          ) =>
        assertEquals((era, viewEra), (Era.Dijkstra, Era.Conway))
      case other => fail(s"expected the wrong era, got $other")
  }

  test("a header larger than the view allows is refused by the envelope, before its signature") {
    val big = dijkstra.head.copy(raw = Bytes(Vector.fill(70000)(0.toByte)))
    check(big) match
      case AnnouncementCheck.Checked(
            Left(AnnouncementInvalidity.EnvelopeInvalid(EnvelopeError.HeaderSizeTooLarge(size, _)))
          ) =>
        assertEquals(size, BigInt(70000))
      case other => fail(s"expected the header's size refused, got $other")
  }

  test("a header whose signature fails is refused by the protocol checks") {
    // the second header's signature does not sign the first header's body
    val forged = dijkstra.head.copy(kesSignature = dijkstra(1).kesSignature)
    check(forged) match
      case AnnouncementCheck.Checked(Left(AnnouncementInvalidity.HeaderInvalid(_))) => ()
      case other => fail(s"expected the header refused, got $other")
  }

  test("a build without Praos2 checks no announcement") {
    assertEquals(rules(ReferenceBuild.Release11_2).announcementRules.isEmpty, true)
  }
