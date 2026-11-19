// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.consensus.{BlockRef, ChainSelection, PraosConfig}
import xyz.kd5ujc.scarab.consensus.HeaderRules.{ForecastReason, Outcome, TimeForecast}
import xyz.kd5ujc.scarab.consensus.state.{ChainDepState, MirrorState}
import xyz.kd5ujc.scarab.ledger.{Block, EnvelopeError, Header, HeaderError, OCertError, Praos2Error}
import xyz.kd5ujc.scarab.ledger.state.{SlotNo, WithOrigin}
import xyz.kd5ujc.scarab.node.read.ChainRead.given
import xyz.kd5ujc.scarab.protocol.{Era, HfcHeader, NtNHeader, ReferenceBuild, StateLayout}
import xyz.kd5ujc.scarab.storage.haskell.state.HaskellLedgerFile

/** The node's header rules ([[NodeHeaderRules]], which the header gate runs on every peer's
  * headers) on Dijkstra headers the reference adopted, under `--reference-build leios-b004fe0c`:
  * each header read from its ChainSync envelope as the peer path reads it ([[NtNHeader]],
  * [[Header.fromNtN]]) and validated by Praos2 against the ledger view forecast from the anchor, as
  * the reference's ChainSync client validates it (ouroboros-consensus 2416493b
  * `MiniProtocol/ChainSync/Client.hs:1934-1990`).
  *
  *   - Across the boundary: the reference cluster's chain of `fixtures/dijkstra-devnet`
  *     (cardano-node b004fe0c), whose Conway blocks 0 to 32 go through the node's rules, then its
  *     Dijkstra headers 33 to 40 through the header rules with the Conway state as the anchor.
  *   - Within Dijkstra: the public testnet's ledger state at slot 2,168,639
  *     (`fixtures/leios/state/testnet-2168639`) as the anchor, and the eight headers that follow it
  *     on that chain (`fixtures/leios/testnet/headers-after-2168639.tsv`), checked against the
  *     stake of 154 pools.
  *
  * Both chains are at major version 12, which the node's maximum of 11 refuses until it enters
  * Dijkstra (#54); the suite's configurations raise it to 12, as the reference's
  * `ExperimentalHardForksEnabled` does. With the node's own maximum every Dijkstra header stays
  * refused as of the wrong era, in either build.
  */
class DijkstraHeaderRulesSuite extends munit.FunSuite:
  private def hex(s: String): Bytes = Bytes.fromHex(s).fold(e => fail(e.message), identity)
  private def rows(file: Path): Vector[Array[String]] =
    Files
      .readAllLines(file)
      .asScala
      .toVector
      .filter(l => l.nonEmpty && !l.startsWith("#"))
      .map(_.split('\t'))
  private def withMax(praos: PraosConfig, max: Int): PraosConfig =
    praos.copy(forks = praos.forks.copy(maxMajorProtVer = max))
  private def rules(setup: NodeSetup, praos: PraosConfig, build: ReferenceBuild) =
    NodeHeaderRules[MirrorState](
      praos,
      setup.globals.copy(referenceBuild = build),
      setup.byronRules,
      setup.handOver
    )

  /** A header as a peer's ChainSync sends it, `[7, tag24(header)]`, read as the peer path does. */
  private def received(setup: NodeSetup, envelope: Bytes): Header =
    NtNHeader.codec
      .decode(envelope)
      .left
      .map(_.toString)
      .flatMap(Header.fromNtN(_, setup.geometry.byron.epochSlots).left.map(_.message))
      .fold(e => fail(e), identity)
  private def envelope(raw: Bytes): Bytes =
    HfcHeader.encode(HfcHeader.Dijkstra(raw)).fold(e => fail(e.toString), identity)

  /** `raw` with the byte at `at` (counted from the end when negative) changed. */
  private def flipped(raw: Bytes, at: Int): Bytes =
    val i = if at < 0 then raw.size + at else at
    val h = raw.hex
    hex(h.patch(2 * i, if h.slice(2 * i, 2 * i + 2) == "00" then "01" else "00", 2))

  private def ref(h: Header): BlockRef = BlockRef(h.slot, h.hash, h.blockNo)

  /** The header states after each of `headers`, from `start`, each checked from `anchor`. */
  private def fold(
      r: NodeHeaderRules[MirrorState],
      anchor: MirrorState,
      tip: Option[BlockRef],
      start: NodeHeaderState,
      headers: Vector[Header]
  ): NodeHeaderState =
    headers.foldLeft(start) { (state, h) =>
      r.validate(anchor, tip, state, h) match
        case Outcome.Valid(next) => next
        case other               => fail(s"block ${h.blockNo} at slot ${h.slot}: $other")
    }

  private def praos2(state: NodeHeaderState) = state.protocol.map(_.dep) match
    case Some(ChainDepState.Praos2(p)) => p
    case other                         => fail(s"not a Praos2 state: $other")

  // --- across the boundary: the devnet ---------------------------------------------------------

  private val devnet = Path.of("fixtures", "dijkstra-devnet")
  private lazy val devnetSetup = NodeSetup
    .fromConfigFile(ReferenceBuild.LeiosB004fe0c)(devnet.resolve("genesis/config.json"))
    .fold(e => fail(e.message), identity)
  private lazy val devnetPraos = withMax(devnetSetup.praos, 12)

  /** The states after each Conway block, the genesis state first. */
  private lazy val conway: Vector[(Header, MirrorState)] =
    rows(devnet.resolve("blocks.tsv"))
      .filter(_(0) == "7")
      .map(r => hex(r(4)))
      .scanLeft(Option.empty[Header] -> devnetSetup.mirrorStart) { case ((_, state), raw) =>
        val block = Block
          .decode(raw, devnetSetup.geometry.byron.epochSlots)
          .fold(e => fail(e.message), identity)
        Some(block.header) -> MirrorSteps
          .block(devnetSetup, state, block.header, raw, Some(devnetPraos))
          .fold(f => fail(s"block ${block.header.blockNo} refused: ${f.reason}"), identity)
      }
      .collect { case (Some(h), s) => h -> s }
  private lazy val (lastConway, crossing) = conway.last
  private lazy val dijkstraHeaders: Vector[Header] =
    rows(devnet.resolve("headers.tsv"))
      .filter(_(0) == "8")
      .map(r => received(devnetSetup, envelope(hex(r(4)))))

  test("a peer's Dijkstra headers read from ChainSync as Dijkstra headers") {
    assertEquals(dijkstraHeaders.map(_.era).distinct, Vector(Era.Dijkstra))
    assertEquals(dijkstraHeaders.map(_.blockNo), (33 to 40).map(BigInt(_)).toVector)
    assertEquals(lastConway.blockNo, BigInt(32))
  }

  test("every Dijkstra header the reference adopted passes from the Conway anchor") {
    val r = rules(devnetSetup, devnetPraos, ReferenceBuild.LeiosB004fe0c)
    val start = r.at(crossing)
    val end = fold(r, crossing, Some(ref(lastConway)), start, dijkstraHeaders)
    val state = praos2(end)
    assertEquals(
      state.praos.lastSlot,
      WithOrigin.At(SlotNo.fromUnsigned(dijkstraHeaders.last.slot.toLong))
    )
    // the epoch nonce of epoch 1, which Praos2's tick made from the translated Praos state
    assertNotEquals(state.praos.epochNonce, crossing.protocol.view.epochNonce)
    (end: NodeHeaderState) match {
      case NodeHeaderState.Shelley(_, previous, _, _) =>
        assertEquals(previous, Some(ref(dijkstraHeaders.last)))
      case other => fail(s"not a Shelley header state: $other")
    }
  }

  test("a Dijkstra header whose KES signature was changed is refused as the reference refuses it") {
    val r = rules(devnetSetup, devnetPraos, ReferenceBuild.LeiosB004fe0c)
    val first = dijkstraHeaders.head
    // the last byte of the header's body signature (`InvalidKesSignatureOCERT`)
    val forged = received(devnetSetup, envelope(flipped(first.raw, -1)))
    assertEquals(
      r.validate(crossing, Some(ref(lastConway)), r.at(crossing), forged),
      Outcome.Invalid(
        HeaderError.Praos2(Praos2Error.Certificate(OCertError.KesSignatureRejected))
      )
    )
  }

  test("a Dijkstra header past the forecast range of its anchor waits for the node's chain") {
    val r = rules(devnetSetup, devnetPraos, ReferenceBuild.LeiosB004fe0c)
    // an anchor whose tip's stability window (75 slots) ends before the first Dijkstra slot: the
    // bound of a forecast across the boundary is then the current era's
    val (early, anchor) = conway.findLast(_._1.slot + 1 + 75 <= dijkstraHeaders.head.slot).get
    assertEquals(
      r.validate(anchor, Some(ref(early)), r.at(crossing), dijkstraHeaders.head),
      Outcome.Later(ForecastReason.Horizon(dijkstraHeaders.head.slot, early.slot))
    )
  }

  test(
    "with the node's own maximum version every build refuses a Dijkstra header as of the wrong era"
  ) {
    ReferenceBuild.values.foreach { build =>
      val r = rules(devnetSetup, withMax(devnetSetup.praos, 11), build)
      assertEquals(
        r.validate(crossing, Some(ref(lastConway)), r.at(crossing), dijkstraHeaders.head),
        Outcome.Invalid(
          HeaderError.Envelope(EnvelopeError.WrongEra(Era.Dijkstra, Era.Conway, 1, 12))
        ),
        build.name
      )
    }
  }

  test("the gate's time forecast places the Dijkstra headers from the Conway anchor") {
    val r = rules(devnetSetup, devnetPraos, ReferenceBuild.LeiosB004fe0c)
    dijkstraHeaders.foreach { h =>
      r.forecastTime(crossing, Some(ref(lastConway)), r.at(crossing), h) match
        case TimeForecast.Known(_) => ()
        case other                 => fail(s"block ${h.blockNo}: $other")
    }
  }

  test("chain selection breaks a tie of Dijkstra headers with Conway's restricted VRF rule") {
    assertEquals(
      ChainSelection.tiebreakerFor(Era.Dijkstra),
      ChainSelection.tiebreakerFor(Era.Conway)
    )
  }

  // --- within Dijkstra: the public testnet -------------------------------------------------------

  private val testnet = Path.of("fixtures", "leios")
  private lazy val testnetSetup = NodeSetup
    .fromConfigFile(ReferenceBuild.LeiosB004fe0c)(testnet.resolve("testnet/config/config.json"))
    .fold(e => fail(e.message), identity)

  /** The testnet's state at slot 2,168,639 as the node's anchor: its ledger and header state read
    * in the Leios layout, with the bookkeeping of the node's genesis state (the header rules read
    * none of it on this chain).
    */
  private lazy val testnetAnchor: MirrorState =
    val raw = Bytes.wrap(Files.readAllBytes(testnet.resolve("state/testnet-2168639/state")))
    val file =
      HaskellLedgerFile.decode(raw, StateLayout.Leios).fold(e => fail(e.render), identity)
    val ext = file.ext.getOrElse(fail("no Shelley-based ledger"))
    testnetSetup.mirrorStart.copy(ext = ext)
  private val anchorTip =
    BlockRef(
      BigInt(2168639),
      hex("aed73a1050263343a16d9a980b2f6dce1152c2e959b69987230a9e5f4844f619"),
      BigInt(97273)
    )
  private lazy val testnetHeaders: Vector[Header] =
    rows(testnet.resolve("testnet/headers-after-2168639.tsv"))
      .map(r => received(testnetSetup, hex(r(4))))
  private lazy val testnetRules =
    rules(testnetSetup, withMax(testnetSetup.praos, 12), ReferenceBuild.LeiosB004fe0c)

  /** The anchor's header state, as the gate starts a session at its tip. */
  private lazy val testnetStart: NodeHeaderState =
    testnetRules.at(testnetAnchor) match
      case s: NodeHeaderState.Shelley => s.copy(previous = Some(anchorTip))
      case other                      => fail(s"not a Shelley header state: $other")

  test("the testnet's headers after slot 2,168,639 pass from its ledger state, leaders checked") {
    assertEquals(testnetAnchor.era, Era.Dijkstra)
    assertEquals(testnetHeaders.head.prevHash, Some(anchorTip.hash))
    val end = fold(testnetRules, testnetAnchor, Some(anchorTip), testnetStart, testnetHeaders)
    val state = praos2(end)
    assertEquals(state.praos.lastSlot, WithOrigin.At(SlotNo.fromUnsigned(2168776)))
    // the last header's announcement, by its issuer
    assertEquals(
      state.announcement.map(_.announcement.size),
      Some(BigInt("16ca7", 16).toLong)
    )
  }

  test("the gate's time forecast places the testnet's headers from its ledger state") {
    testnetHeaders.foreach { h =>
      testnetRules.forecastTime(testnetAnchor, Some(anchorTip), testnetStart, h) match
        case TimeForecast.Known(_) => ()
        case other                 => fail(s"block ${h.blockNo}: $other")
    }
  }

  test("a certificate claimed too soon after the announcement is refused before the signatures") {
    // the fourth header (slot 2,168,718) with its certificate flag set: its predecessor (slot
    // 2,168,714) announced an endorser block, whose certificate may come 14 slots later at the
    // earliest (3 * 1 s + 4 s + 7 s, slots of 1 s); the Leios checks run before the signature
    // checks (`updateChainDepState` of Praos2, `Protocol/Praos2.hs:131-132`, ouroboros-consensus
    // 2416493b), so the changed bytes
    // are refused for the certificate, not the signature
    val before =
      fold(testnetRules, testnetAnchor, Some(anchorTip), testnetStart, testnetHeaders.take(3))
    val fourth = testnetHeaders(3)
    val unflagged = fourth.raw.hex
    assertEquals(unflagged.sliding(8).count(_ == "820c00f4"), 1)
    val flagged =
      received(testnetSetup, envelope(hex(unflagged.replace("820c00f4", "820c00f5"))))
    assertEquals(
      testnetRules.validate(testnetAnchor, Some(anchorTip), before, flagged),
      Outcome.Invalid(
        HeaderError.Praos2(
          Praos2Error.LeiosCertTooYoung(
            SlotNo.fromUnsigned(2168714),
            SlotNo.fromUnsigned(2168718),
            SlotNo.fromUnsigned(2168728)
          )
        )
      )
    )
  }
