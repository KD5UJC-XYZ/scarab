// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.consensus

import io.circe.{Decoder, Json, parser}
import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.node.NodeSetup
import xyz.kd5ujc.scarab.consensus.state.{ChainDepState, ProtocolState}
import xyz.kd5ujc.scarab.crypto.Blake2b
import xyz.kd5ujc.scarab.crypto.vrf.PraosLeaderThreshold
import xyz.kd5ujc.scarab.crypto.kes.Sum6Kes
import xyz.kd5ujc.scarab.crypto.vrf.PraosVrfCertificate as Vrf
import xyz.kd5ujc.scarab.ledger.{Header, ProviderDefect, Verdicts}
import xyz.kd5ujc.scarab.ledger.{
  EnvelopeError,
  LeiosHeader,
  Nonce,
  OCertError,
  PoolDistribution,
  PraosError
}
import xyz.kd5ujc.scarab.ledger.state.{
  BlockIssuer,
  BlockNo,
  EpochNo,
  KeyHash,
  ProtVer,
  SlotNo,
  Word64,
  WithOrigin
}
import xyz.kd5ujc.scarab.node.read.ChainRead.given
import xyz.kd5ujc.scarab.node.read.InEra
import xyz.kd5ujc.scarab.protocol.{Era, HfcHeader}
import xyz.kd5ujc.scarab.protocol.LeiosWord.{Word16, Word32}

/** Native validateHeader/tickHeaderState at consensus 2416493b over the maintained testnet
  * encodings. The forecast uses genesis stake for epochs zero and one; no block ledger is run.
  */
class Praos2ReferenceSuite extends munit.FunSuite:
  private val source = Path.of("fixtures/leios/testnet")
  private val setup = NodeSetup
    .fromConfigFile(source.resolve("config/config.json"))
    .fold(e => fail(e.message), identity)
  private val pools = setup.mirrorStart
    .ledger(None)
    .toOption
    .flatten
    .fold(fail("genesis ledger unavailable")) { case InEra.Of(_, ledger) =>
      ledger.leaderPools(nextEpoch = false)
    }
  private def word16(n: Int): Word16 = Word16.from(n).fold(e => fail(e.toString), identity)
  private def word32(n: Long): Word32 = Word32.from(n).fold(e => fail(e.toString), identity)
  private val view = Praos2.LedgerView(
    pools,
    word16(1100),
    word32(90112),
    ProtVer(Word64.fromUnsigned(12), Word64.zero),
    word32(1000),
    word32(4000),
    word32(7000),
    word32(40000)
  )
  private val config = Praos2.Config(
    setup.praos,
    Word64.fromUnsigned(12),
    Praos2.EpochInfo.fromGeometry(setup.praos.geometry)
  )
  private val initial = ProtocolState.initial(Era.Conway, setup.identity.shelleyGenesis).dep match
    case ChainDepState.Praos(p) => Praos2.ChainState(None, Praos2.State(p, None))
    case other                  => fail(s"unexpected initial protocol: $other")

  private val headers = Files
    .readAllLines(source.resolve("headers-from-origin.tsv"))
    .asScala
    .filter(l => l.nonEmpty && !l.startsWith("#"))
    .toVector
    .map { line =>
      val envelope = Bytes.fromHex(line.split('\t')(4)).fold(e => fail(e.message), identity)
      HfcHeader.decode(envelope) match
        case Right(HfcHeader.Dijkstra(raw)) =>
          LeiosHeader.decode(raw).fold(e => fail(e.message), identity)
        case other => fail(s"unexpected header: $other")
    }

  private def nonce(n: Nonce): Json = n match
    case Nonce.Neutral => Json.Null
    case Nonce.Hash(h) => Json.fromString(h.hex)

  private def bytes(hex: String): Bytes = Bytes.fromHex(hex).fold(e => fail(e.message), identity)
  private def field[A: Decoder](json: Json, key: String): A =
    json.hcursor.get[A](key).fold(e => fail(e.message), identity)
  private def optional[A: Decoder](json: Json, key: String): Option[A] =
    field[Option[A]](json, key)
  private def slot(n: BigInt): SlotNo = SlotNo.fromBigInt(n).fold(e => fail(e.toString), identity)
  private def word(n: BigInt): Word64 = Word64
    .fromBigInt(xyz.kd5ujc.scarab.ledger.state.VocabError.Kind.OcertCounter, n)
    .fold(e => fail(e.toString), identity)
  private def issuer(h: LeiosHeader): KeyHash[BlockIssuer] =
    KeyHash
      .fromBytes[BlockIssuer](Blake2b.hash224.hash(h.issuerVkey))
      .fold(e => fail(e.toString), identity)
  private def readHeader(hex: String): LeiosHeader =
    LeiosHeader.decode(bytes(hex)).fold(e => fail(e.message), identity)

  private def testView(options: Json): Praos2.LedgerView =
    val distribution = optional[Vector[Json]](options, "poolOverride").fold(pools) { entries =>
      PoolDistribution(
        entries.map(p => bytes(field[String](p, "pool")) -> field[BigInt](p, "stake")).toMap,
        entries.map(p => bytes(field[String](p, "pool")) -> bytes(field[String](p, "vrf"))).toMap
      )
    }
    view.copy(
      pools = distribution,
      protocolVersion =
        ProtVer(word(optional[BigInt](options, "major").getOrElse(BigInt(12))), Word64.zero),
      maxHeaderSize = word16(optional[Int](options, "headerLimit").getOrElse(1100)),
      maxBodySize = word32(optional[Long](options, "bodyLimit").getOrElse(90112L)),
      maxEbReferencesSize = word32(optional[Long](options, "ebLimit").getOrElse(40000L)),
      announcementPeriodMillis =
        word32(optional[Long](options, "announcementMillis").getOrElse(1000L)),
      votePeriodMillis = word32(optional[Long](options, "voteMillis").getOrElse(4000L)),
      diffusionPeriodMillis = word32(optional[Long](options, "diffusionMillis").getOrElse(7000L))
    )

  private def testConfig(options: Json): Praos2.Config =
    val info = config.epochInfo
    val modifiedInfo = new Praos2.EpochInfo:
      def epochOf(s: SlotNo): Either[Praos2.Defect, EpochNo] = info.epochOf(s)
      def firstSlot(e: EpochNo): Either[Praos2.Defect, SlotNo] = info.firstSlot(e)
      def epochSize(e: EpochNo): Either[Praos2.Defect, Word64] = info.epochSize(e)
      def slotLengthMillis(s: SlotNo): Either[Praos2.Defect, BigInt] =
        optional[BigInt](options, "slotMicros").fold(info.slotLengthMillis(s))(us =>
          Right(us / 1000)
        )
    config.copy(
      praos = config.praos.copy(
        slotsPerKesPeriod =
          optional[BigInt](options, "kesPeriodSlots").getOrElse(config.praos.slotsPerKesPeriod),
        maxKesEvolutions =
          optional[BigInt](options, "maxKesEvolutions").getOrElse(config.praos.maxKesEvolutions),
        checkpoints =
          optional[String](options, "checkpointHash").fold(HeaderCheckpoints.empty) { hash =>
            HeaderCheckpoints(
              Map(optional[BigInt](options, "checkpointBlock").getOrElse(BigInt(0)) -> bytes(hash))
            )
          }
      ),
      maxMajor = word(optional[BigInt](options, "maxMajor").getOrElse(BigInt(12))),
      epochInfo = modifiedInfo
    )

  private def testState(
      state: Praos2.ChainState,
      header: LeiosHeader,
      options: Json
  ): Praos2.ChainState =
    val p = state.protocol.praos
    val next = p.copy(
      lastSlot =
        if optional[Boolean](options, "originLastSlot").contains(true) then WithOrigin.Origin
        else optional[BigInt](options, "lastSlot").fold(p.lastSlot)(s => WithOrigin.At(slot(s))),
      epochNonce =
        optional[String](options, "epochNonce").fold(p.epochNonce)(h => Nonce.Hash(bytes(h))),
      ocertCounters = optional[BigInt](options, "counter").fold(p.ocertCounters)(n =>
        p.ocertCounters.updated(issuer(header), word(n))
      )
    )
    val announcement =
      if optional[Boolean](options, "clearAnnouncement").contains(true) then None
      else
        optional[String](options, "announcementHeader").fold(state.protocol.announcement) { raw =>
          val h = readHeader(raw)
          Some(
            Praos2.AnnouncedBy(
              issuer(h),
              h.leios.announcement.getOrElse(fail("missing supplied announcement"))
            )
          )
        }
    val tip =
      if optional[Boolean](options, "originTip").contains(true) then None
      else
        state.tip.map(t =>
          t.copy(
            blockNo = optional[BigInt](options, "tipBlock").fold(t.blockNo)(n =>
              BlockNo.fromBigInt(n).fold(e => fail(e.toString), identity)
            ),
            slot = optional[BigInt](options, "tipSlot").fold(t.slot)(slot)
          )
        )
    Praos2.ChainState(tip, Praos2.State(next, announcement))

  private def verdict(
      error: Praos2.Failure,
      header: LeiosHeader,
      state: Praos2.State,
      cfg: Praos2.Config,
      ledger: Praos2.LedgerView
  ): Json =
    def n(value: BigInt): Json = Json.fromBigInt(value)
    def h(value: Bytes): Json = Json.fromString(value.hex)
    def tagged(tag: String, args: Json*): Json =
      Json.obj("tag" -> Json.fromString(tag), "args" -> Json.arr(args*))
    error match
      case Praos2.Error.UnexpectedBlockNo(wanted, got) =>
        tagged("UnexpectedBlockNo", n(wanted.toBigInt), n(got))
      case Praos2.Error.UnexpectedSlotNo(wanted, got) =>
        tagged("UnexpectedSlotNo", n(wanted.toBigInt), n(got))
      case Praos2.Error.UnexpectedPrevHash(wanted, got) =>
        tagged("UnexpectedPrevHash", wanted.fold(Json.Null)(h), got.fold(Json.Null)(h))
      case Praos2.Error.Envelope(EnvelopeError.CheckpointMismatch(no, wanted, got)) =>
        tagged("CheckpointMismatch", n(no), h(wanted), h(got))
      case Praos2.Error.Envelope(EnvelopeError.ObsoleteNode(v, bound)) =>
        tagged("ObsoleteNode", n(v), n(bound))
      case Praos2.Error.Envelope(EnvelopeError.HeaderSizeTooLarge(size, bound)) =>
        tagged("HeaderSizeTooLarge", n(size), n(bound))
      case Praos2.Error.Envelope(EnvelopeError.BlockSizeTooLarge(size, bound)) =>
        tagged("BlockSizeTooLarge", n(size), n(bound))
      case Praos2.Error.LeiosEbTooBig(size, bound) =>
        tagged("LeiosEbTooBig", n(BigInt(size)), n(BigInt(bound.toLong)))
      case Praos2.Error.LeiosCertWithoutAnnouncement => tagged("LeiosCertWithoutAnnouncement")
      case Praos2.Error.LeiosCertTooYoung(a, s, first) =>
        tagged("LeiosCertTooYoung", n(a.toBigInt), n(s.toBigInt), n(first.toBigInt))
      case Praos2.Error.KesBeforeStart(start, current) =>
        tagged("KESBeforeStartOCERT", n(start), n(current))
      case Praos2.Error.KesAfterEnd(current, start, bound) =>
        tagged("KESAfterEndOCERT", n(current), n(start), n(bound))
      case Praos2.Error.CounterTooSmall(old, fresh) =>
        tagged("CounterTooSmallOCERT", n(old.toBigInt), n(fresh.toBigInt))
      case Praos2.Error.CounterOverIncremented(old, fresh) =>
        tagged("CounterOverIncrementedOCERT", n(old.toBigInt), n(fresh.toBigInt))
      case Praos2.Error.Certificate(OCertError.NoCounter(key)) =>
        tagged("NoCounterForKeyHashOCERT", h(key))
      case Praos2.Error.Certificate(OCertError.OpCertSignatureRejected) =>
        tagged("InvalidSignatureOCERT", n(header.opCert.sequence), n(header.opCert.kesPeriod))
      case Praos2.Error.Certificate(OCertError.KesSignatureRejected) =>
        val current = header.slot / cfg.praos.slotsPerKesPeriod
        tagged(
          "InvalidKesSignatureOCERT",
          n(current),
          n(header.opCert.kesPeriod),
          n(current - header.opCert.kesPeriod),
          n(cfg.praos.maxKesEvolutions)
        )
      case Praos2.Error.Praos(PraosError.IssuerWithoutStake(key)) => tagged("VRFKeyUnknown", h(key))
      case Praos2.Error.Praos(PraosError.UnregisteredPool(key))   => tagged("VRFKeyUnknown", h(key))
      case Praos2.Error.Praos(PraosError.VrfKeyMismatch(key, actual, registered)) =>
        assertEquals(registered.size, 1)
        tagged("VRFKeyWrongVRFKey", h(key), h(registered.toVector.head), h(actual))
      case Praos2.Error.Praos(PraosError.VrfRejected(_)) =>
        tagged("VRFKeyBadProof", n(header.slot), nonce(state.praos.epochNonce))
      case Praos2.Error.Praos(PraosError.NotElected(_, _)) =>
        val value =
          PraosLeaderThreshold.leaderValue(header.vrf.output).fold(e => fail(e.toString), identity)
        val stake = ledger.pools.stake(Blake2b.hash224.hash(header.issuerVkey))
        val divisor = stake.gcd(ledger.pools.total)
        tagged(
          "VRFLeaderValueTooBig",
          n(value),
          Json.obj(
            "numerator" -> n(stake / divisor),
            "denominator" -> n(ledger.pools.total / divisor)
          ),
          Json.fromBigDecimal(BigDecimal("0.05"))
        )
      case other => fail(s"unexpected native comparison failure: $other")

  private def stateJson(state: Praos2.State): Json =
    val p = state.praos
    Json.obj(
      "lastSlot" -> (p.lastSlot match
        case WithOrigin.Origin => Json.Null
        case WithOrigin.At(s)  => Json.fromBigInt(s.toBigInt)),
      "counters" -> Json.obj(p.ocertCounters.toVector.map { (key, counter) =>
        key.bytes.hex -> Json.fromBigInt(counter.toBigInt)
      }*),
      "evolvingNonce" -> nonce(p.evolvingNonce),
      "candidateNonce" -> nonce(p.candidateNonce),
      "epochNonce" -> nonce(p.epochNonce),
      "previousEpochNonce" -> nonce(p.previousEpochNonce),
      "labNonce" -> nonce(p.labNonce),
      "lastEpochBlockNonce" -> nonce(p.lastEpochBlockNonce),
      "announcement" -> state.announcement.fold(Json.Null) { a =>
        Json.obj(
          "issuer" -> Json.fromString(a.issuer.bytes.hex),
          "hash" -> Json.fromString(a.announcement.hash.bytes.hex),
          "size" -> Json.fromLong(a.announcement.size)
        )
      }
    )

  test(
    "all 1100 recorded headers and every Praos2 state field agree with the executed native validator"
  ) {
    val expected = Files
      .readAllLines(Path.of("fixtures/reference/leios-praos2/headers.jsonl"))
      .asScala
      .toVector
      .map(line => parser.parse(line).fold(e => fail(e.message), identity))
    assertEquals(headers.size, 1100)
    assertEquals(expected.size, headers.size)
    val result = headers.zip(expected).zipWithIndex.foldLeft(initial) {
      case (state, ((header, native), index)) =>
        val slot = SlotNo.fromBigInt(header.slot).fold(e => fail(e.toString), identity)
        val ticked = Praos2.tick(config, view, slot, state).fold(e => fail(e.toString), identity)
        val next =
          Praos2.validate(config, header, ticked).fold(e => fail(s"header $index: $e"), identity)
        assertEquals(native.hcursor.get[Boolean]("accepted"), Right(true), index)
        assertEquals(native.hcursor.get[Int]("index"), Right(index))
        assertEquals(
          stateJson(next.protocol),
          native.hcursor.downField("state").focus.fold(fail("missing state"))(identity),
          index
        )
        next
    }
    assertNotEquals(result.protocol.praos.epochNonce, initial.protocol.praos.epochNonce)
  }

  Files
    .readAllLines(Path.of("fixtures/reference/leios-praos2/cases.jsonl"))
    .asScala
    .toVector
    .foreach { line =>
      val row = parser.parse(line).fold(e => fail(e.message), identity)
      test(field[String](row, "name")) {
        val header = readHeader(field[String](row, "header"))
        val options = field[Json](row, "options")
        val expected = field[Json](row, "result")
        val cfg = testConfig(options)
        val ledger = testView(options)
        val prefix = headers.take(field[Int](row, "context")).foldLeft(initial) { (state, h) =>
          val ticked =
            Praos2.tick(config, view, slot(h.slot), state).fold(e => fail(e.toString), identity)
          Praos2.validate(config, h, ticked).fold(e => fail(e.toString), identity)
        }
        val prior = testState(prefix, header, options)
        val actualTick =
          Praos2.tick(cfg, ledger, slot(header.slot), prior).fold(e => fail(e.toString), identity)
        val ticked =
          if optional[Boolean](options, "skipTick").contains(true) then
            actualTick.copy(chain = prior)
          else actualTick
        optional[String](options, "operation").getOrElse("validate") match
          case "certificationSlot" =>
            val millis = cfg.epochInfo
              .slotLengthMillis(slot(header.slot))
              .fold(e => fail(e.toString), identity)
            val first = Praos2
              .minCertificationSlot(
                millis,
                ledger,
                slot(optional[BigInt](options, "lastSlot").getOrElse(BigInt(0)))
              )
              .fold(e => fail(e.toString), identity)
            assertEquals(first.toBigInt, field[BigInt](expected, "earliest"))
          case operation =>
            val outcome = operation match
              case "validate" => Praos2.validate(cfg, header, ticked).map(_.protocol)
              case "protocol" => Praos2.validateProtocol(cfg, header, ticked).map(_.protocol)
              case "tick"     => Right(ticked.chain.protocol)
              case "reupdate" => Praos2.reupdate(cfg, header, ticked)
              case other      => fail(s"unknown fixture operation: $other")
            outcome match
              case Right(next) =>
                assertEquals(field[Boolean](expected, "accepted"), true)
                assertEquals(stateJson(next), field[Json](expected, "state"))
              case Left(error) =>
                assertEquals(field[Boolean](expected, "accepted"), false, error)
                assertEquals(
                  verdict(error, header, ticked.chain.protocol, cfg, ledger),
                  field[Json](field[Json](expected, "failure"), "verdict")
                )
      }
    }

  test("tick asks only the old epoch forecast when entering the next epoch") {
    val old = EpochNo.fromUnsigned(0)
    val first = SlotNo.fromUnsigned(0)
    val limited = new Praos2.EpochInfo:
      def epochOf(s: SlotNo): Either[Praos2.Defect, EpochNo] =
        Either.cond(s == first, old, Praos2.Defect.SlotOutsideForecast(s))
      def firstSlot(e: EpochNo): Either[Praos2.Defect, SlotNo] =
        Either.cond(e == old, first, Praos2.Defect.EpochOutsideForecast(e))
      def epochSize(e: EpochNo): Either[Praos2.Defect, Word64] =
        Either.cond(e == old, Word64.fromUnsigned(100), Praos2.Defect.EpochOutsideForecast(e))
      def slotLengthMillis(s: SlotNo): Either[Praos2.Defect, BigInt] =
        Left(Praos2.Defect.SlotOutsideForecast(s))
    val previous = initial.protocol.praos.copy(lastSlot = WithOrigin.At(first))
    val result = Praos2
      .tick(
        config.copy(epochInfo = limited),
        view,
        SlotNo.fromUnsigned(100),
        initial.copy(protocol = initial.protocol.copy(praos = previous))
      )
      .fold(e => fail(e.toString), identity)
    assertEquals(
      result.chain.protocol.praos.epochNonce,
      previous.candidateNonce.combine(previous.lastEpochBlockNonce)
    )
    assertEquals(result.chain.protocol.praos.previousEpochNonce, previous.epochNonce)
    assertEquals(result.chain.protocol.praos.lastEpochBlockNonce, previous.labNonce)
  }

  test("missing epoch forecast is a defect before a header verdict") {
    val h = headers.head
    val missing = Praos2.Defect.EpochOutsideForecast(EpochNo.fromUnsigned(0))
    val info = new Praos2.EpochInfo:
      def epochOf(s: SlotNo): Either[Praos2.Defect, EpochNo] =
        Left(Praos2.Defect.SlotOutsideForecast(s))
      def firstSlot(e: EpochNo): Either[Praos2.Defect, SlotNo] =
        Left(Praos2.Defect.EpochOutsideForecast(e))
      def epochSize(e: EpochNo): Either[Praos2.Defect, Word64] =
        Left(Praos2.Defect.EpochOutsideForecast(e))
      def slotLengthMillis(s: SlotNo): Either[Praos2.Defect, BigInt] =
        Left(Praos2.Defect.SlotOutsideForecast(s))
    assertEquals(
      Praos2.tick(config.copy(epochInfo = info), view, slot(h.slot), initial),
      Left(missing)
    )
  }

  test("a machine-word successor overflow has no header verdict") {
    val h = headers(5)
    val maximum = BigInt(2).pow(64) - 1
    val highNo =
      initial.copy(tip = Some(Praos2.Tip(Bytes.empty, BlockNo.fromUnsigned(-1L), slot(0))))
    val highSlot =
      initial.copy(tip = Some(Praos2.Tip(Bytes.empty, BlockNo.fromUnsigned(4), slot(maximum))))
    val blockResult = Praos2.validate(config, h, Praos2.Ticked(highNo, view))
    val slotResult = Praos2.validate(config, h, Praos2.Ticked(highSlot, view))
    assertEquals(
      blockResult,
      Left(Praos2.Defect.SuccessorOverflow(xyz.kd5ujc.scarab.ledger.state.VocabError.Kind.BlockNo))
    )
    assertEquals(
      slotResult,
      Left(Praos2.Defect.SuccessorOverflow(xyz.kd5ujc.scarab.ledger.state.VocabError.Kind.SlotNo))
    )
  }

  test("a zero slot length or KES period is a configuration defect") {
    assertEquals(
      Praos2.minCertificationSlot(0, view, slot(0)),
      Left(Praos2.Defect.NonPositiveSlotLength(0))
    )
    val bad = config.copy(praos = config.praos.copy(slotsPerKesPeriod = 0))
    assertEquals(
      Praos2.validateProtocol(bad, headers.head, Praos2.Ticked(initial, view)),
      Left(Praos2.Defect.NonPositiveKesPeriod(0))
    )
  }

  private def failingProvider(cert: Option[ProviderDefect], vrf: Option[Vrf.Result]): Verdicts =
    new Verdicts:
      export Verdicts.Direct.{witness, tpraosVrf, keyHash, takePrepared}
      def opCert(
          h: Header.Shelley,
          period: BigInt,
          evolutions: BigInt
      ): Either[OCertError | ProviderDefect, Unit] =
        cert.fold(Verdicts.Direct.opCert(h, period, evolutions))(Left(_))
      def praosVrf(input: Vrf.Input, key: Bytes, proof: Bytes, output: Bytes): Vrf.Result =
        vrf.getOrElse(Verdicts.Direct.praosVrf(input, key, proof, output))

  test("certificate and VRF provider defects keep their original typed causes") {
    val cause = new IllegalStateException("injected provider failure")
    val cert = ProviderDefect.Kes(Sum6Kes.Failure.Defect(cause))
    val vrf = Vrf.Result.InternalFailure(cause)
    val h = headers.head
    val ticked =
      Praos2.tick(config, view, slot(h.slot), initial).fold(e => fail(e.toString), identity)
    val certConfig =
      config.copy(praos = config.praos.copy(verdicts = failingProvider(Some(cert), Some(vrf))))
    val vrfConfig =
      config.copy(praos = config.praos.copy(verdicts = failingProvider(None, Some(vrf))))
    assertEquals(Praos2.validate(certConfig, h, ticked), Left(cert))
    assertEquals(Praos2.validate(vrfConfig, h, ticked), Left(ProviderDefect.VrfVerifier(vrf)))
  }
