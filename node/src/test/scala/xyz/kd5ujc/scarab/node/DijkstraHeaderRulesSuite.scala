// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.consensus.{
  BlockRef,
  ByronTip,
  ChainSelection,
  DijkstraCrossing,
  HardForkConfig,
  Praos2,
  PraosConfig
}
import xyz.kd5ujc.scarab.consensus.HeaderRules.{ForecastReason, Outcome, TimeForecast}
import xyz.kd5ujc.scarab.consensus.state.{ChainDepState, MirrorState}
import xyz.kd5ujc.scarab.ledger.{
  Block,
  CrossingForecastFailure,
  EnvelopeError,
  Header,
  HeaderDefect,
  HeaderError,
  LeiosHeader,
  OCertError,
  Praos2Defect,
  Praos2Error,
  Verdicts
}
import xyz.kd5ujc.scarab.ledger.rules.Globals
import xyz.kd5ujc.scarab.ledger.state.{
  BlockNo,
  Conway,
  Dijkstra,
  NewEpochState,
  ShelleyBasedEra,
  SlotNo,
  Word64,
  WithOrigin
}
import xyz.kd5ujc.scarab.node.read.InEra
import xyz.kd5ujc.scarab.node.read.ChainRead.given
import xyz.kd5ujc.scarab.protocol.{Era, HfcHeader, NtNHeader, ReferenceBuild, StateLayout}
import xyz.kd5ujc.scarab.crypto.vrf.PraosVrfCertificate as Vrf
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
  * Under the Leios build the node's maximum major version is the configured one, 12 on both chains
  * (#54); at a maximum of 11, the release builds', every Dijkstra header is refused as of the wrong
  * era, in either build.
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
  private lazy val devnetPraos = devnetSetup.praos

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
      case NodeHeaderState.Shelley(_, previous, _, _, _) =>
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

  // --- from a Byron anchor: the era-walking capture's genesis -----------------------------------

  /** The node's setup for `fixtures/eras` (Byron epochs of 50 slots, k = 5, Shelley's stability
    * window 75), whose genesis state is Byron's.
    */
  private lazy val erasSetup = NodeSetup
    .fromDirectory(ReferenceBuild.Default)(Path.of("fixtures", "eras", "genesis"))
    .fold(e => fail(e.message), identity)

  /** The Byron header state whose last header is the devnet's block 32 (put at `slot`), which the
    * first Dijkstra header extends: the envelope checks of every era hold, so the era match
    * decides.
    */
  private def byronParent(
      r: NodeHeaderRules[MirrorState],
      slot: BigInt = lastConway.slot
  ): NodeHeaderState =
    r.at(erasSetup.mirrorStart) match
      case b: NodeHeaderState.Byron =>
        b.copy(
          previous = Some(ByronTip(lastConway.hash, slot / 50, slot, false)),
          previousBlockNo = Some(lastConway.blockNo)
        )
      case other => fail(s"not a Byron header state: $other")

  /** The capture's rules with the Shelley boundary `shelley` (configured, which also fixes the
    * Byron span of the era history, or derived from the Byron ledger when none) and the hard forks
    * `forks`.
    */
  private def byronRules(shelley: Option[BigInt], forks: Map[Era, BigInt]) =
    val praos = erasSetup.praos
    val span = praos.geometry.byron
    // the capture is read with the 11.1 build's setup: the Leios build's maximum is given here
    NodeHeaderRules[MirrorState](
      withMax(
        praos.copy(
          geometry = praos.geometry.copy(byron =
            span.copy(epochs = shelley.getOrElse(span.epochs), derived = shelley.isEmpty)
          ),
          forks = HardForkConfig.fromEpochs(forks)
        ),
        12
      ),
      erasSetup.globals.copy(referenceBuild = ReferenceBuild.LeiosB004fe0c),
      erasSetup.byronRules.map(_.copy(configuredShelleyEpoch = shelley)),
      erasSetup.handOver
    )

  test("from a Byron anchor with no Shelley boundary yet, a Dijkstra header meets the Byron view") {
    assertEquals(erasSetup.mirrorStart.era, Era.Byron)
    val r = byronRules(None, Map.empty)
    // the first Dijkstra header put at a slot (synthetic: neither the envelope nor the era match
    // reads its signatures), after a parent at slot 9
    def at(slot: BigInt) = dijkstraHeaders.head match
      case h: Header.Shelley => h.copy(slot = slot)
      case other             => fail(s"not a Shelley-based header: $other")
    val parent = byronParent(r, 9)
    // the Byron forecast from the genesis is at its last slot, 0, and reaches 2k + 1 = 11 slots,
    // exclusive (`Byron/Ledger/Ledger.hs:315-323`, ouroboros-consensus 2416493b): at slot 2k = 10
    // the era match refuses the header, its epoch Byron's (50 slots)
    r.validate(erasSetup.mirrorStart, None, parent, at(10)) match
      case Outcome.Invalid(
            HeaderError.Envelope(EnvelopeError.WrongEra(Era.Dijkstra, Era.Byron, epoch, _))
          ) =>
        assertEquals(epoch, BigInt(0))
      case other => fail(s"expected the era mismatch with Byron, got $other")
    // at slot 2k + 1 = 11 it is past the range: no verdict yet
    assertEquals(
      r.validate(erasSetup.mirrorStart, None, parent, at(11)),
      Outcome.Later(ForecastReason.Horizon(11, 0))
    )
  }

  test("from a Byron anchor whose Shelley boundary is Allegra's too, the view stops at Shelley") {
    // Shelley and Allegra both at epoch 4 (slot 200): the forecast crosses into Shelley only, and
    // reaches 75 slots past the boundary
    val r = byronRules(Some(4), Map(Era.Shelley -> 4, Era.Allegra -> 4))
    val first = dijkstraHeaders.head
    r.validate(erasSetup.mirrorStart, None, byronParent(r), first) match
      case Outcome.Invalid(
            HeaderError.Envelope(EnvelopeError.WrongEra(Era.Dijkstra, Era.Shelley, epoch, _))
          ) =>
        assertEquals(epoch, BigInt(4))
      case other => fail(s"expected the era mismatch with Shelley, got $other")
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
    "at a maximum major version of 11, the release builds', every build refuses a Dijkstra header as of the wrong era"
  ) {
    // the node's own setup of the devnet, of the Leios build, has the configured 12
    assertEquals(devnetSetup.praos.forks.maxMajorProtVer, 12)
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

  /** The devnet's configuration with Conway entered at epoch 1, as Dijkstra is: its genesis state
    * is Babbage's, and the end of Babbage at epoch 1 is also Conway's.
    */
  private def withBabbageStart[A](body: NodeSetup => A): A =
    val dir = Files.createTempDirectory("dijkstra-babbage-start")
    try
      Files.list(devnet.resolve("genesis")).iterator.asScala.foreach { f =>
        val text = Files.readString(f)
        Files.writeString(
          dir.resolve(f.getFileName),
          if f.getFileName.toString == "config.json" then
            text.replace("\"TestConwayHardForkAtEpoch\": 0", "\"TestConwayHardForkAtEpoch\": 1")
          else text
        )
      }
      body(
        NodeSetup
          .fromConfigFile(ReferenceBuild.LeiosB004fe0c)(dir.resolve("config.json"))
          .fold(e => fail(e.message), identity)
      )
    finally
      Files.list(dir).iterator.asScala.foreach(Files.delete)
      Files.delete(dir)

  test(
    "from a Babbage anchor whose era ends with Conway's, a Dijkstra header is of the wrong era"
  ) {
    withBabbageStart { setup =>
      val anchor = setup.mirrorStart
      assertEquals(anchor.era, Era.Babbage)
      val r = rules(setup, setup.praos, ReferenceBuild.LeiosB004fe0c)
      // the header extends the devnet's block 32, as the candidate chain it came on does
      val prev = r.at(anchor) match
        case s: NodeHeaderState.Shelley => s.copy(previous = Some(ref(lastConway)))
        case other                      => fail(s"not a Shelley header state: $other")
      val first = dijkstraHeaders.head
      // the hard-fork combinator's forecast takes the anchor one era on, to Conway, whatever the
      // header's era (`oneForecast`): past its range no verdict yet, within it the era match fails
      assertEquals(
        r.validate(anchor, None, prev, first),
        Outcome.Later(ForecastReason.Horizon(first.slot, -1))
      )
      // the same anchor said to be at slot 199, a synthetic tip within the range
      r.validate(anchor, Some(ref(lastConway)), prev, first) match
        case Outcome.Invalid(
              HeaderError.Envelope(EnvelopeError.WrongEra(Era.Dijkstra, Era.Conway, epoch, _))
            ) =>
          assertEquals(epoch, BigInt(1))
        case other => fail(s"expected the era mismatch, got $other")
    }
  }

  /** Verdicts that count the cryptographic checks asked of them. */
  private final class Counting extends Verdicts:
    var asked = 0
    def witness(vkey: Bytes, signature: Bytes, message: Bytes) =
      asked += 1; Verdicts.Direct.witness(vkey, signature, message)
    def opCert(header: Header.Shelley, slotsPerKesPeriod: BigInt, maxKesEvolutions: BigInt) =
      asked += 1; Verdicts.Direct.opCert(header, slotsPerKesPeriod, maxKesEvolutions)
    def praosVrf(input: Vrf.Input, vkey: Bytes, proof: Bytes, output: Bytes) =
      asked += 1; Verdicts.Direct.praosVrf(input, vkey, proof, output)
    def tpraosVrf(alpha: Bytes, vkey: Bytes, proof: Bytes, output: Bytes) =
      asked += 1; Verdicts.Direct.tpraosVrf(alpha, vkey, proof, output)
    def keyHash(vkey: Bytes) = Verdicts.Direct.keyHash(vkey)
    def takePrepared(hash: Bytes) = None

  test("a header the envelope refuses reaches no cryptographic check") {
    val nes: NewEpochState[Conway] = MirrorSteps.ledger(crossing) match
      case Some(i: InEra.Of[e]) =>
        i.era match
          case ShelleyBasedEra.SConway => i.read.nes.fold(e => fail(e.toString), identity)
          case other                   => fail(s"not in Conway: $other")
      case other => fail(s"no Shelley-based ledger: $other")
    val globals = Globals(devnetSetup.globals, devnetPraos.geometry)
    val first = LeiosHeader.decode(dijkstraHeaders.head.raw).fold(e => fail(e.message), identity)
    val slot = SlotNo.fromUnsigned(first.slot.toLong)
    val view = DijkstraCrossing
      .forecast(
        globals,
        globals,
        SlotNo.fromUnsigned(200),
        slot,
        WithOrigin.At(SlotNo.fromUnsigned(lastConway.slot.toLong)),
        nes
      )
      .fold(e => fail(e.toString), identity)
    val state =
      praos2(rules(devnetSetup, devnetPraos, ReferenceBuild.LeiosB004fe0c).at(crossing) match
        case s: NodeHeaderState.Shelley =>
          s.copy(state =
            s.state.copy(dep = ChainDepState.Praos2(DijkstraCrossing.chainDepState(s.state.view)))
          )
        case other => fail(s"not a Shelley header state: $other"))
    def run(blockNo: Long) =
      val counting = Counting()
      val config = Praos2.Config(
        devnetPraos.copy(verdicts = counting),
        Word64.fromUnsigned(12),
        Praos2.EpochInfo.fromGeometry(devnetPraos.geometry)
      )
      val tip = Praos2.Tip(lastConway.hash, BlockNo.fromUnsigned(blockNo), SlotNo.fromUnsigned(199))
      val ticked = Praos2
        .tick(config, view, slot, Praos2.ChainState(Some(tip), state))
        .fold(e => fail(e.toString), identity)
      Praos2.validate(config, first, ticked) -> counting.asked
    // the right parent: the checks run, and pass
    val (valid, checks) = run(32)
    assert(valid.isRight, valid)
    assert(checks > 0)
    // a parent one block short: refused by the envelope, before any signature or proof is checked
    assertEquals(
      run(31),
      (Left(Praos2Error.UnexpectedBlockNo(BlockNo.fromUnsigned(32), 33)), 0)
    )
  }

  test("a Praos2 parameter that cannot hold leaves a header undecided, never refused or waiting") {
    val config = devnetPraos.copy(slotsPerKesPeriod = 0)
    val r = rules(devnetSetup, config, ReferenceBuild.LeiosB004fe0c)
    assertEquals(
      r.validate(crossing, Some(ref(lastConway)), r.at(crossing), dijkstraHeaders.head),
      Outcome.Undecided(HeaderDefect.Praos2(Praos2Defect.NonPositiveKesPeriod(0)))
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
    rules(testnetSetup, testnetSetup.praos, ReferenceBuild.LeiosB004fe0c)

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

  test(
    "Dijkstra's own forecast reaches to the stability window past the tip's successor, exclusive"
  ) {
    val nes: NewEpochState[Dijkstra] = testnetAnchor.ledger(None).toOption.flatten match
      case Some(i: InEra.Of[e]) =>
        i.era match
          case ShelleyBasedEra.SDijkstra => i.read.nes.fold(e => fail(e.toString), identity)
          case other                     => fail(s"not in Dijkstra: $other")
      case other => fail(s"no Shelley-based ledger: $other")
    val globals = Globals(testnetSetup.globals, testnetSetup.praos.geometry)
    val window = globals.stabilityWindow
    assertEquals(window, BigInt(6480))
    val tip = WithOrigin.At(SlotNo.fromUnsigned(anchorTip.slot.toLong))
    val bound = SlotNo.fromUnsigned((anchorTip.slot + 1 + window).toLong)
    def at(slot: BigInt) =
      DijkstraCrossing.forecastInEra(globals, SlotNo.fromUnsigned(slot.toLong), tip, nes)
    assert(at(anchorTip.slot).isRight)
    assert(at(anchorTip.slot + window).isRight)
    assertEquals(
      at(anchorTip.slot + 1 + window),
      Left(CrossingForecastFailure.OutsideForecastRange(tip, bound, bound))
    )
  }
