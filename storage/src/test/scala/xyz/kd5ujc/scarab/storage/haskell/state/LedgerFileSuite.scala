// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage.haskell.state

import cats.syntax.all.*
import java.nio.file.{Files, Path}
import munit.ScalaCheckSuite
import org.scalacheck.{Arbitrary, Gen}
import org.scalacheck.Prop.forAll
import scala.jdk.CollectionConverters.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.consensus.state.{StateGen as _, *}
import xyz.kd5ujc.scarab.consensus.state.StateGen as ConsensusGen
import xyz.kd5ujc.scarab.consensus.state.StateGen.given
import xyz.kd5ujc.scarab.ledger.state.*
import xyz.kd5ujc.scarab.ledger.state.EpochStateGen.given
import xyz.kd5ujc.scarab.ledger.state.VocabularyGen.given
import xyz.kd5ujc.scarab.models.runtime.{Codec, DecodeError, Writer}
import xyz.kd5ujc.scarab.protocol.Era

import DecodeError.Kind
import HaskellStateCodecs.{*, given}

/** The envelopes of the Haskell `state` file: the laws of every codec on generated values of every
  * era, hand-derived bytes of the small encodings (each cites the Haskell instance), typed
  * refusals, the committed fixtures written by the Haskell node, and the real file of a Preview
  * node when `HASKELL_STATE_FILE` names one.
  */
class LedgerFileSuite extends ScalaCheckSuite:
  private def hex(s: String): Bytes =
    Bytes.fromHex(s.filterNot(_.isWhitespace)).fold(fail(_), identity)

  private def bytesOf(body: Writer => Unit): Bytes = StateGen.item(body)

  private def arb[A: Arbitrary]: Gen[A] = Arbitrary.arbitrary[A]

  private def read[A](codec: Codec[A], bytes: Bytes): Either[DecodeError, A] =
    codec.decodeBytes(bytes, HaskellState.limits)

  private def readOk[A](codec: Codec[A], bytes: Bytes): A =
    read(codec, bytes).fold(e => fail(e.render), identity)

  private def failure[A](codec: Codec[A], bytes: Bytes): DecodeError =
    read(codec, bytes).fold(identity, a => fail(s"decoded $a"))

  /** The two laws of a codec on its generator, and that what is generated is writable. */
  private def laws[A](name: String, gen: Gen[A], codec: Codec[A]): Unit =
    property(s"$name: decode after encode is the identity, and encode after decode is on bytes") {
      forAll(gen) { a =>
        val bytes = codec.encodeUnchecked(a)
        assertEquals(codec.check(a), None, "a generated value is writable")
        assertEquals(codec.write(a), Right(bytes))
        val back = readOk(codec, bytes)
        assertEquals(back, a)
        assertEquals(codec.encodeUnchecked(back), bytes)
      }
    }

  // ---- generators

  private val hash32 = "ab" * 32

  /** A window of PBFT signers as the state holds it: slots ascending. */
  private val pbft: Gen[PBftState] =
    Gen
      .listOf(Gen.zip(Gen.choose(1L, 5L), arb[KeyHash[Genesis]]))
      .map { steps =>
        val slots = steps.map(_._1).scanLeft(0L)(_ + _).tail
        PBftState.ofWindow(
          slots.zip(steps.map(_._2)).map((s, k) => PBftSigner(SlotNo.fromUnsigned(s), k)).toVector
        )
      }

  private def chainDep(era: Era): Gen[ChainDepState] =
    if era == Era.Byron then pbft.map(ChainDepState.PBft(_))
    else ConsensusGen.chainDepState(era)

  private val tipInfo: Gen[TipInfo] =
    Gen.oneOf(
      Gen
        .zip(arb[Hash32], Gen.oneOf(IsEBB.values.toSeq))
        .map((h, ebb) => TipInfo.ByronTip(h, ebb)),
      Gen.zip(Gen.oneOf(shelleyBased), arb[Hash32]).map((e, h) => TipInfo.ShelleyBasedTip(e, h))
    )

  private lazy val shelleyBased: Seq[ShelleyBasedEra[?]] = Seq(
    ShelleyBasedEra.SShelley,
    ShelleyBasedEra.SAllegra,
    ShelleyBasedEra.SMary,
    ShelleyBasedEra.SAlonzo,
    ShelleyBasedEra.SBabbage,
    ShelleyBasedEra.SConway
  )

  private val annTipGen: Gen[AnnTip] =
    Gen.zip(arb[SlotNo], arb[BlockNo], tipInfo).map((s, b, i) => AnnTip(s, b, i))

  private def shelleyIn[E <: EraTag](using ShelleyBasedEra[E]): Gen[LedgerFileEra] =
    Gen
      .zip(
        arb[WithOrigin[ShelleyTip]],
        StateGen.newEpochState[E],
        arb[ShelleyTransition],
        Gen.option(arb[PerasRoundNo])
      )
      .map((t, nes, tr, p) =>
        LedgerFileEra.Mirrored(EraLedgerState.inEra(ShelleyLedgerState[E](t, nes, tr, p)))
      )

  private def ledgerEra(era: Era): Gen[LedgerFileEra] = era match
    case Era.Byron =>
      // an item of the Byron ledger state's kind: some array, of which nothing is known
      Gen
        .choose(0, 5)
        .map(n =>
          LedgerFileEra.ByronItem(bytesOf { w =>
            w.arrayHead(n)
            (0 until n).foreach(_ => w.nul())
          })
        )
    case Era.Shelley => shelleyIn[Shelley]
    case Era.Allegra => shelleyIn[Allegra]
    case Era.Mary    => shelleyIn[Mary]
    case Era.Alonzo  => shelleyIn[Alonzo]
    case Era.Babbage => shelleyIn[Babbage]
    case Era.Conway  => shelleyIn[Conway]

  private val fileGen: Gen[HaskellLedgerFile] =
    for
      era <- ConsensusGen.anyEra
      ledger <- ConsensusGen.telescope(era, ledgerEra)
      tip <- Gen.oneOf(Gen.const(WithOrigin.Origin), annTipGen.map(WithOrigin.At(_)))
      deps <- ConsensusGen.telescope(era, chainDep)
    yield HaskellLedgerFile(ledger, HeaderState(tip, deps))

  // ---- the laws

  laws("PBftState", pbft, pbftState)
  laws("TPraosState", ConsensusGen.tpraosState, tpraosState)
  laws("PraosState", ConsensusGen.praosState, praosState)
  laws("AnnTip", annTipGen, annTip)
  laws("Bound", arb[Bound], Envelope.bound)

  property("HaskellLedgerFile: decode after encode is the identity, on bytes, in every era") {
    forAll(fileGen) { file =>
      assertEquals(HaskellLedgerFile.encode(file).map(_.size > 0), Right(true))
      val bytes = HaskellLedgerFile.encode(file).fold(e => fail(e.violation.render), identity)
      val back = HaskellLedgerFile.decode(bytes).fold(e => fail(e.render), identity)
      assert(back === file)
      assertEquals(HaskellLedgerFile.encode(back), Right(bytes))
    }
  }

  // ---- bytes worked out from the Haskell instances

  test("PBftState: [1, {key: [slots, newest first]}], the lists indefinite (State.hs:300-317)") {
    def signer(slot: Long, key: String) =
      PBftSigner(
        SlotNo.fromUnsigned(slot),
        KeyHash.fromBytes[Genesis](hex(key * 28)).fold(e => fail(e.toString), identity)
      )
    val state = PBftState.ofWindow(Vector(signer(5, "01"), signer(7, "02"), signer(9, "01")))
    val expected = "8201 a2 581c" + "01" * 28 + "9f 09 05 ff" + "581c" + "02" * 28 + "9f 07 ff"
    assertEquals(pbftState.encodeUnchecked(state).hex, expected.filterNot(_.isWhitespace))
    assertEquals(readOk(pbftState, hex(expected)), state)
  }

  test("a PBftState whose counts are not those of its window is not writable") {
    val state = PBftState.ofWindow(Vector.empty)
    val bad = state.copy(counts =
      Sorted.map(
        Seq(
          KeyHash.fromBytes[Genesis](hex("03" * 28)).toOption.toList.head -> Word64.fromUnsigned(1)
        )
      )
    )
    assert(pbftState.write(bad).isLeft)
  }

  test("TPraosState: [1, [last slot, [[counters, evolving, candidate], [epoch, prev], lab]]]") {
    // lastSlot Origin is [] (encodeWithOrigin); the nonces are [0] (Neutral); the empty counters a0
    val expected = "8201 82 80 83 83 a0 8100 8100 82 8100 8100 8100"
    val state = readOk(tpraosState, hex(expected))
    assertEquals(state.lastSlot, WithOrigin.Origin)
    assertEquals(tpraosState.encodeUnchecked(state).hex, expected.filterNot(_.isWhitespace))
  }

  test("PraosState: [0, [last slot [1, n], counters, six nonces]] (Praos.hs:281-308)") {
    val expected = "8200 88 820105 a0" + " 8100" * 6
    val state = readOk(praosState, hex(expected))
    assertEquals(state.lastSlot, WithOrigin.At(SlotNo.fromUnsigned(5)))
    assertEquals(praosState.encodeUnchecked(state).hex, expected.filterNot(_.isWhitespace))
  }

  test("AnnTip: [era index, [slot, hash, block number]], Byron's with the boundary flag") {
    val conway = s"8206 83 05 5820$hash32 07"
    val tip = readOk(annTip, hex(conway))
    assertEquals(tip.slot, SlotNo.fromUnsigned(5))
    assertEquals(tip.blockNo, BlockNo.fromUnsigned(7))
    assertEquals(annTip.encodeUnchecked(tip).hex, conway.filterNot(_.isWhitespace))
    val byron = s"8200 84 05 5820$hash32 07 f5"
    val b = readOk(annTip, hex(byron))
    assert(b.info.isInstanceOf[TipInfo.ByronTip])
    assertEquals(annTip.encodeUnchecked(b).hex, byron.filterNot(_.isWhitespace))
  }

  test("Bound: the time is an Integer, a bignum from 2^64 picoseconds (Summary.hs:526-550)") {
    val big = Bound(BigInt(1) << 64, BigInt(5), BigInt(1))
    assertEquals(Envelope.bound.encodeUnchecked(big).hex, "83c249010000000000000000" + "0501")
    val small = Bound(BigInt(3), BigInt(5), BigInt(1))
    assertEquals(Envelope.bound.encodeUnchecked(small).hex, "83030501")
    assertEquals(readOk(Envelope.bound, Envelope.bound.encodeUnchecked(big)), big)
  }

  // ---- refusals

  private def telescopeOf(items: Writer => Unit, count: Int): Bytes =
    bytesOf { w =>
      w.arrayHead(count)
      items(w)
    }

  private def bound(t: Long, slot: Long, epoch: Long)(w: Writer): Unit =
    w.arrayHead(3); w.uint(t); w.uint(slot); w.uint(epoch)

  private def past(from: Long, to: Long)(w: Writer): Unit =
    w.arrayHead(2); bound(from, from, from)(w); bound(to, to, to)(w)

  private val chainDepTelescope = Envelope.telescope(chainDepStateOf)

  test("a telescope of no items or of more eras than the chain has is refused") {
    assert(failure(chainDepTelescope, bytesOf(_.arrayHead(0))).kind.isInstanceOf[Kind.BadArity])
    val eight = telescopeOf(w => (0 until 8).foreach(_ => w.nul()), 8)
    assert(failure(chainDepTelescope, eight).kind.isInstanceOf[Kind.BadArity])
  }

  test("a telescope whose eras do not abut is refused as a violation, naming the telescope") {
    // Byron ended at 5, Shelley started at 6: one past era, then the current one (Shelley, TPraos)
    val tpraos = bytesOf { w =>
      w.arrayHead(2); w.uint(1L); w.arrayHead(2); w.arrayHead(1); w.uint(0L)
      w.arrayHead(3)
      w.arrayHead(3); w.mapHead(0); w.arrayHead(1); w.uint(0L); w.arrayHead(1); w.uint(0L)
      w.arrayHead(2); w.arrayHead(1); w.uint(0L); w.arrayHead(1); w.uint(0L)
      w.arrayHead(1); w.uint(0L)
    }
    def at(start: Long) = bytesOf { w =>
      w.arrayHead(2)
      past(0, 5)(w)
      w.arrayHead(2); bound(start, start, start)(w); w.raw(tpraos)
    }
    assert(read(chainDepTelescope, at(5)).isRight)
    val e = failure(chainDepTelescope, at(6))
    e.kind match
      case Kind.Violation(v) => assertEquals(v.typeName, "Telescope")
      case other             => fail(s"expected a violation, got $other")
  }

  test(
    "a Bound with a Peras round, a snapshot of the older layout and a wrong version are refused"
  ) {
    val four = bytesOf { w =>
      w.arrayHead(4); w.uint(0L); w.uint(0L); w.uint(0L); w.uint(0L)
    }
    assert(failure(Envelope.bound, four).kind.isInstanceOf[Kind.BadArity])
    val legacy = bytesOf { w =>
      w.arrayHead(3); w.nul(); w.nul(); w.nul()
    }
    assert(failure(haskellLedgerFile, legacy).kind.isInstanceOf[Kind.BadArity])
    val v2 = bytesOf { w =>
      w.arrayHead(2); w.uint(2L); w.nul()
    }
    val e = failure(haskellLedgerFile, v2)
    assert(e.kind.isInstanceOf[Kind.LiteralMismatch], e.render)
    assertEquals(e.path, List("version"))
    assert(failure(praosState, hex("8201 80")).kind.isInstanceOf[Kind.LiteralMismatch])
  }

  test("the Byron state of the node has no Haskell form: it is not writable") {
    val byron = LedgerFileEra.Mirrored(
      EraLedgerState.ByronCurrent(xyz.kd5ujc.scarab.consensus.ByronState.initial)
    )
    assert(ledgerFileEra(Era.Byron).write(byron).isLeft)
    assert(ledgerFileEra(Era.Byron).write(LedgerFileEra.ByronItem(hex("80"))).isRight)
  }

  test("the pulser of a mid-epoch reward update is written back as it was read") {
    given ShelleyBasedEra[Conway] = ShelleyBasedEra.SConway
    val nes = xyz.kd5ujc.scarab.storage.haskell.state.StateGen
      .newEpochState[Conway]
      .sample
      .getOrElse(fail("no state"))
    val pulser = hex("9f 01 a1 01 02 ff")
    val snap = arb[RewardSnapShot].sample.getOrElse(fail("no snapshot"))
    val state = nes.copy[Conway](ru = Some(PulsingRewUpdate.Pulsing(snap, RewardPulser(pulser))))
    val bytes = HaskellState.encode(state).fold(e => fail(e.violation.render), identity)
    val back = HaskellState.decode[Conway](bytes).fold(e => fail(e.render), identity)
    assertEquals(back.ru, state.ru)
    assertEquals(HaskellState.encode(back), Right(bytes))
  }

  // ---- the meta file

  test("meta: the fields of a real file, and the refusals") {
    val real = """{"backend":"utxohd-mem","checksum":3851921054,"tablesCodecVersion":1}"""
    assertEquals(
      SnapshotMeta.parse(real),
      Right(SnapshotMeta(SnapshotBackend.InMemory, 3851921054L, 1))
    )
    assertEquals(
      SnapshotMeta
        .parse("""{"backend":"utxohd-lsm","checksum":1,"tablesCodecVersion":1}""")
        .map(_.backend),
      Right(SnapshotBackend.Lsm)
    )
    assertEquals(
      SnapshotMeta.parse("""{"backend":"rocks","checksum":1,"tablesCodecVersion":1}"""),
      Left(MetaError.UnknownBackend("rocks"))
    )
    assertEquals(
      SnapshotMeta.parse(
        """{"backend":"utxohd-mem","checksum":4294967296,"tablesCodecVersion":1}"""
      ),
      Left(MetaError.BadChecksum(BigInt(4294967296L)))
    )
    assertEquals(
      SnapshotMeta.parse("""{"backend":"utxohd-mem","checksum":1,"tablesCodecVersion":2}"""),
      Left(MetaError.UnknownTablesCodecVersion(BigInt(2)))
    )
    assert(SnapshotMeta.parse("not json").left.exists(_.isInstanceOf[MetaError.JsonSyntax]))
    assertEquals(
      SnapshotMeta.parse("""{"backend":"utxohd-mem"}"""),
      Left(MetaError.MissingField(MetaField.Checksum))
    )
  }

  // ---- the files the Haskell node wrote

  private val fixtureRoot = Path.of("fixtures/haskell-state")

  private val fixtures: Vector[Path] =
    if Files.isDirectory(fixtureRoot) then
      Files
        .list(fixtureRoot)
        .iterator
        .asScala
        .filter(p => Files.isRegularFile(p.resolve("state")))
        .toVector
        .sortBy(_.getFileName.toString)
    else Vector.empty

  test("the fixtures directory has the committed states of the Haskell node") {
    assert(fixtures.nonEmpty, s"no fixture under $fixtureRoot")
  }

  fixtures.foreach { dir =>
    val name = dir.getFileName.toString
    test(s"fixture $name: the Haskell node's state is written again byte for byte") {
      val (result, file) = RealLedgerFileCheck.run(dir.resolve("state")).fold(fail(_), identity)
      assert(result.identical, s"first difference at ${result.firstDifference}")
      // the directory is named <era>-<slot>: the era and the tip it is named for are the file's
      assertEquals(name.takeWhile(_ != '-'), file.era.toString.toLowerCase)
      assertEquals(
        file.header.tip match
          case WithOrigin.At(t)  => t.slot.word.toBigInt.toString
          case WithOrigin.Origin => "origin"
        ,
        name.dropWhile(_ != '-').drop(1)
      )
      assertEquals(file.ledger.past.size, file.era.index)
      assertEquals(file.header.chainDep.era, file.era)
    }
    test(s"fixture $name: the meta file is the reference's") {
      val meta =
        SnapshotMeta.parse(Files.readString(dir.resolve("meta"))).fold(e => fail(e.show), identity)
      assertEquals(meta.backend, SnapshotBackend.InMemory)
      assertEquals(meta.tablesCodecVersion, 1)
    }
    test(s"fixture $name: the derived fields are the ones the mirror derives") {
      val (_, file) = RealLedgerFileCheck.run(dir.resolve("state")).fold(fail(_), identity)
      Derivations.ofFile(file).foreach { d =>
        // the VRF key hashes are kept from protocol version 10 only
        assertEquals(d.vrfExtra, 0)
        assert(d.vrfRecorded == 0 || d.vrfMissing == 0)
        assert(d.pdIsSet || file.era.ordinal < Era.Shelley.ordinal)
        assertEquals(d.delegatorsDiffering, 0)
      }
    }
  }

  private val realFile: Option[Path] = sys.env.get("HASKELL_STATE_FILE").map(Path.of(_))

  test(
    "the real Preview state of a Haskell node is written again byte for byte (HASKELL_STATE_FILE)"
  ) {
    assume(realFile.isDefined, "HASKELL_STATE_FILE is not set")
    realFile.foreach { path =>
      val (result, file) = RealLedgerFileCheck.run(path).fold(fail(_), identity)
      assert(result.identical, s"first difference at ${result.firstDifference}")
      assertEquals(file.era, Era.Conway)
      Derivations.ofFile(file).foreach { d =>
        assertEquals(d.vrfMissing + d.vrfExtra, 0)
        assert(d.pdIsSet)
        assertEquals(d.delegatorsDiffering, 0)
      }
    }
  }
