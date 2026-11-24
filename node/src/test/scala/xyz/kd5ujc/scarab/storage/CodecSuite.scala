// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage

import cats.Eq
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.protocol.{Era, ReferenceBuild}
import xyz.kd5ujc.scarab.consensus.BlockRef
import xyz.kd5ujc.scarab.consensus.FollowState
import xyz.kd5ujc.scarab.consensus.ByronState
import xyz.kd5ujc.scarab.consensus.state.{
  ChainDepState,
  EraLedgerState,
  MirrorExtras,
  ProtocolExtras
}
import xyz.kd5ujc.scarab.node.NodeSetup
import xyz.kd5ujc.scarab.consensus.*
import xyz.kd5ujc.scarab.ledger.Nonce
import xyz.kd5ujc.scarab.ledger.*
import xyz.kd5ujc.scarab.codec.Rational
import munit.ScalaCheckSuite
import org.scalacheck.{Gen, Prop}
import org.scalacheck.Prop.*

import NodeCodecs.given
import Gens.given

/** Round-trip properties for every persisted type, truncation detection and golden encodings. */
class CodecSuite extends ScalaCheckSuite:
  override def scalaCheckTestParameters =
    super.scalaCheckTestParameters.withMinSuccessfulTests(60)

  private def roundTrip[A](name: String)(using c: Codec[A], g: Gens[A]): Unit =
    property(s"round trip: $name") {
      forAll(g.gen) { a =>
        Codec.decode[A](Codec.encode(a)) match
          case Right(back) => Eq.fromUniversalEquals[A].eqv(a, back) :| s"decoded $back"
          case Left(error) => Prop.falsified :| error.message
      }
    }

  roundTrip[BigInt]("BigInt")
  roundTrip[Bytes]("Bytes")
  roundTrip[Rational]("Rational")
  roundTrip[Map[Bytes, BigInt]]("Map")
  roundTrip[NativeScript]("NativeScript")
  roundTrip[Credential]("Credential")
  roundTrip[Address]("Address")
  roundTrip[Value]("Value")
  roundTrip[TxOut]("TxOut")
  roundTrip[TxIn]("TxIn")
  roundTrip[PoolParams]("PoolParams")
  roundTrip[ProtocolParams]("ProtocolParams")
  roundTrip[Nonce]("Nonce")
  roundTrip[Era]("Era")
  roundTrip[Account]("Account")
  // Governance actions and proposals are persisted by the Haskell-section codec only
  // (`NodeTypeCodecs`), whose laws `HaskellStateCodecSuite` checks on the proposals.
  roundTrip[ChainDepState]("ChainDepState")
  roundTrip[ProtocolExtras]("ProtocolExtras")
  roundTrip[FollowState]("FollowState")
  roundTrip[ByronState]("ByronState")
  roundTrip[BlockRef]("BlockRef")
  roundTrip[ConsensusExtras]("ConsensusExtras")

  property("a strict prefix of an encoding never decodes") {
    forAll(Gens[ConsensusExtras], Gen.choose(0.0, 1.0)) { (s, fraction) =>
      val bytes = Codec.encode(s)
      val cut = (bytes.length * fraction).toInt.min(bytes.length - 1)
      Codec.decode[ConsensusExtras](bytes.take(cut)).isLeft
    }
  }

  property("an encoding with trailing bytes does not decode") {
    forAll(Gens[BlockRef]) { r =>
      Codec.decode[BlockRef](Codec.encode(r) :+ 0.toByte).isLeft
    }
  }

  property("decoding arbitrary bytes never throws") {
    forAll(Gen.listOf(Gen.choose(-128, 127).map(_.toByte)).map(_.toArray)) { bytes =>
      Codec.decode[ConsensusExtras](bytes)
      true
    }
  }

  test("the reader never reads past its limit, wherever a value straddles its buffer") {
    // A value that spans a refill used to over-read into the bytes after the limit (the checksum).
    (65400 to 65700).foreach { size =>
      val value = Bytes(Vector.tabulate(size)(_.toByte))
      val payload = Codec.encode(value)
      val tail = Array.fill[Byte](40)(0x5a)
      val stream = java.io.ByteArrayInputStream(payload ++ tail)
      val in = In(stream, payload.length.toLong)
      assertEquals(summon[Codec[Bytes]].read(in), Right(value))
      assertEquals(stream.available(), tail.length, s"size $size")
    }
  }

  test("a huge collection count is rejected without allocating") {
    val bytes = Array[Byte](-1, -1, -1, -1, -1, -1, -1, -1, 127)
    assert(Codec.decode[Map[Bytes, BigInt]](bytes) match {
      case Left(xyz.kd5ujc.scarab.codec.BinaryError.CountExceedsRemaining(_, _)) => true
      case _                                                                     => false
    })
    assert(Codec.decode[Bytes](bytes) match {
      case Left(xyz.kd5ujc.scarab.codec.BinaryError.CountExceedsRemaining(_, _)) => true
      case _                                                                     => false
    })
  }

  // ------------------------------------------------------------------ golden encodings

  private def hex(bytes: Array[Byte]): String = bytes.map(b => f"${b & 0xff}%02x").mkString

  private val hash32 = Bytes(Vector.tabulate(32)(i => (i + 1).toByte))

  test("Byron versions retain the previous three-BigInt layout and schema") {
    import xyz.kd5ujc.scarab.ledger.byron.ByronVersion
    val oldBytes = Codec.encode(BigInt(65535)) ++ Codec.encode(BigInt(65535)) ++
      Codec.encode(BigInt(255))
    val expected = ByronVersion.from(65535, 65535, 255).fold(e => fail(e.message), identity)
    assertEquals(Codec.decode[ByronVersion](oldBytes), Right(expected))
    assertEquals(Codec.encode(expected).toVector, oldBytes.toVector)
    assertEquals(Codec[ByronVersion].shape, "ByronVersion(major:BigInt,minor:BigInt,alt:BigInt)")

  }

  test("Byron version snapshot readers reject values outside the reference widths") {
    import xyz.kd5ujc.scarab.ledger.byron.ByronVersion
    Vector((65536, 0, 0), (0, 65536, 0), (0, 0, 256), (-1, 0, 0)).foreach { (major, minor, alt) =>
      val bytes =
        Codec.encode(BigInt(major)) ++ Codec.encode(BigInt(minor)) ++ Codec.encode(BigInt(alt))
      assert(Codec.decode[ByronVersion](bytes) match {
        case Left(xyz.kd5ujc.scarab.codec.BinaryError.InvalidByronVersion(_, _)) => true
        case _                                                                   => false
      })

    }
  }

  // `Script` is written field by field (#998): its four fields in their declared order, with the
  // shape their derivation gave them. The shape and every byte below are what the derived codec
  // wrote; the shape is part of each sidecar fingerprint further down, through an output's script.
  private def spaced(s: String): String = s.replace(" ", "")
  private val scriptHash = Bytes(Vector.tabulate(28)(i => (0xb0 + i).toByte))
  private val keyHash = Bytes(Vector.tabulate(28)(i => (0x10 + i).toByte))
  private val plutusBytes = Bytes(Vector(0x46, 0x01, 0x00, 0x00, 0x22, 0x00, 0x11).map(_.toByte))
  private val nestedNative = NativeScript.AllOf(
    Vector(
      NativeScript.Sig(keyHash),
      NativeScript.AnyOf(Vector(NativeScript.InvalidBefore(0))),
      NativeScript.NOfK(1, Vector(NativeScript.InvalidHereafter((BigInt(1) << 64) - 1)))
    )
  )
  private val nestedNativeBytes =
    spaced(s"00 1c${hex(scriptHash.toArray)} 01 0103 001c${hex(keyHash.toArray)} 0201 040100") +
      spaced("03 0101 01 05 0900ffffffffffffffff 00")

  test("scripts keep the layout and schema of their four derived fields") {
    assertEquals(
      Codec[Script].shape,
      "Script(kind:ScriptKind{Native|PlutusV1|PlutusV2|PlutusV3|PlutusV4},hash:Bytes," +
        "native:Option[NativeScript{Sig(Bytes)|AllOf(Vector[NativeScript])|" +
        "AnyOf(Vector[NativeScript])|NOfK(BigInt,Vector[NativeScript])|InvalidBefore(BigInt)|" +
        "InvalidHereafter(BigInt)}],plutus:Option[Bytes])"
    )
    val hash = hex(scriptHash.toArray)
    val scripts = Vector(
      Script(ScriptKind.Native, scriptHash, Some(nestedNative)) -> nestedNativeBytes,
      Script(ScriptKind.PlutusV1, scriptHash, None, Some(plutusBytes)) ->
        spaced(s"01 1c$hash 00 01 0746010000220011"),
      Script(ScriptKind.PlutusV1, scriptHash, None) -> spaced(s"01 1c$hash 00 00"),
      Script(ScriptKind.PlutusV2, scriptHash, None, Some(plutusBytes)) ->
        spaced(s"02 1c$hash 00 01 0746010000220011"),
      Script(ScriptKind.PlutusV2, scriptHash, None) -> spaced(s"02 1c$hash 00 00"),
      Script(ScriptKind.PlutusV3, scriptHash, None, Some(plutusBytes)) ->
        spaced(s"03 1c$hash 00 01 0746010000220011"),
      Script(ScriptKind.PlutusV3, scriptHash, None) -> spaced(s"03 1c$hash 00 00"),
      Script(ScriptKind.PlutusV4, scriptHash, None, Some(plutusBytes)) ->
        spaced(s"04 1c$hash 00 01 0746010000220011"),
      Script(ScriptKind.PlutusV4, scriptHash, None) -> spaced(s"04 1c$hash 00 00")
    )
    scripts.foreach { (script, expected) =>
      assertEquals(hex(Codec.encode(script)), expected, script)
      assertEquals(Codec.decode[Script](Codec.encode(script)), Right(script))
    }
  }

  test("the frozen schemas of V5 to V9 name the kinds their files hold, PlutusV4 not among them") {
    assertEquals(
      FrozenScriptKind.ScriptKind.values.toVector.map(FrozenScriptKind.live),
      ScriptKind.values.toVector.filter(_ != ScriptKind.PlutusV4)
    )
    FrozenScriptKind.ScriptKind.values.foreach(k =>
      assertEquals(FrozenScriptKind.live(k).ordinal, k.ordinal, k.toString)
    )
    val frozen = NodeCodecs.scriptOf(FrozenScriptKind.codec)
    assertEquals(
      frozen.shape,
      "Script(kind:ScriptKind{Native|PlutusV1|PlutusV2|PlutusV3},hash:Bytes," +
        "native:Option[NativeScript{Sig(Bytes)|AllOf(Vector[NativeScript])|" +
        "AnyOf(Vector[NativeScript])|NOfK(BigInt,Vector[NativeScript])|InvalidBefore(BigInt)|" +
        "InvalidHereafter(BigInt)}],plutus:Option[Bytes])"
    )
    // the kinds a previous file holds keep their bytes in the frozen schema
    ScriptKind.values.filter(_ != ScriptKind.PlutusV4).foreach { k =>
      val s = Script(k, scriptHash, None, Some(plutusBytes))
      assertEquals(hex(Codec.encode(s)(using frozen)), hex(Codec.encode(s)), k.toString)
      assertEquals(Codec.decode(Codec.encode(s))(using frozen), Right(s), k.toString)
    }
    val v4 = Script(ScriptKind.PlutusV4, scriptHash, None, Some(plutusBytes))
    Codec.decode(Codec.encode(v4))(using frozen) match
      case Left(xyz.kd5ujc.scarab.codec.BinaryError.InvalidVariant("ScriptKind", 4L, 4)) => ()
      case other => fail(s"a frozen schema read a PlutusV4 script: $other")
  }

  test("the frozen schemas of V5 to V9 name the chain-dependent states their files hold") {
    val frozen = FrozenChainDepState.codec
    assert(frozen.shape.startsWith("ChainDepState{PBft("), frozen.shape)
    assert(!frozen.shape.contains("Praos2"), frozen.shape)
    // the live shape is the frozen one with Praos2 appended
    assertEquals(
      NodeCodecs.chainDepState.shape,
      frozen.shape.dropRight(1) + "|Praos2(state:" + NodeCodecs.praos2State.shape + ")}"
    )
  }

  property("the frozen schemas of V5 to V9 keep the bytes of their states and refuse Praos2") {
    val frozen = FrozenChainDepState.codec
    forAll(summon[Gens[ChainDepState]].gen) { state =>
      val bytes = Codec.encode(state)
      assertEquals(hex(Codec.encode(state)(using frozen)), hex(bytes))
      state match
        case ChainDepState.Praos2(_) =>
          Codec.decode(bytes)(using frozen) match
            case Left(xyz.kd5ujc.scarab.codec.BinaryError.InvalidVariant("ChainDepState", 3L, 3)) =>
              ()
            case other => fail(s"a frozen schema read a Praos2 state: $other")
        case ChainDepState.PBft(_) | ChainDepState.TPraos(_) | ChainDepState.Praos(_) =>
          assertEquals(Codec.decode(bytes)(using frozen), Right(state))
    }
  }

  test("an output built with a reference script is stored by the codec, its script as before") {
    val address = Address
      .decode(Bytes(0x61.toByte +: keyHash.value), Address.Mode.Stored)
      .fold(e => fail(e.message), identity)
    val script = Script(ScriptKind.Native, scriptHash, Some(nestedNative))
    val built = TxOut(Bytes.empty, address, Value.coin(2000000), Datum.NoDatum, Some(script), 0)
    val encoded =
      spaced(s"00 1d61${hex(keyHash.toArray)} 00 01 001c${hex(keyHash.toArray)} 00 00") +
        spaced("031e8480 00 00 01") + nestedNativeBytes + "00"
    assertEquals(hex(Codec.encode(built)), encoded)
    // no original encoding: the stored value is the codec's (tag 0)
    assertEquals(hex(UtxoStore.Coding.encodeValue(built)), "00" + encoded)
    assertEquals(UtxoStore.Coding.decodeValue(UtxoStore.Coding.encodeValue(built)), Right(built))
  }

  test("golden: primitives and containers") {
    assertEquals(hex(Codec.encode(BigInt(0))), "0100")
    assertEquals(hex(Codec.encode(BigInt(-1))), "01ff")
    assertEquals(hex(Codec.encode(BigInt(256))), "020100")
    assertEquals(hex(Codec.encode(Bytes(Vector(1, 2, 3)))), "03010203")
    assertEquals(hex(Codec.encode(Option.empty[Int])), "00")
    assertEquals(hex(Codec.encode(Option(300))), "01ac02")
    assertEquals(hex(Codec.encode(Vector(true, false, true))), "03010001")
    assertEquals(hex(Codec.encode(Rational(3, 6))), "01010102")
    assertEquals(hex(Codec.encode(Map(Bytes(Vector(9)) -> BigInt(5)))), "0101090105")
  }

  test("golden: sums are a variant index then the variant") {
    assertEquals(hex(Codec.encode[Nonce](Nonce.Neutral)), "00")
    assertEquals(hex(Codec.encode[Nonce](Nonce.Hash(Bytes(Vector(7))))), "010107")
    assertEquals(hex(Codec.encode[Era](Era.Byron)), "00")
    assertEquals(hex(Codec.encode[Era](Era.Conway)), "06")
    assertEquals(hex(Codec.encode[Credential](Credential.Script(Bytes(Vector(1))))), "010101")
  }

  test("golden: BlockRef") {
    assertEquals(
      hex(Codec.encode(BlockRef(86400, hash32, 4321, boundary = true))),
      "03015180" + "20" + hex(hash32.toArray) + "0210e1" + "01"
    )
  }

  private lazy val initial =
    NodeSetup
      .fromDirectory(ReferenceBuild.Default)(java.nio.file.Path.of("fixtures/eras/genesis"))
      .fold(e => fail(e.message), _.mirrorStart)

  test("golden: the schema fingerprint of the sidecar of the V5 body (V10)") {
    assertEquals(hex(SnapshotSidecar.fingerprint), GoldenSidecarFingerprint)
  }

  test("golden: the frozen sidecar schema of V9 files keeps its fingerprint") {
    assertEquals(hex(SnapshotSidecarV9.fingerprint), GoldenV9SidecarFingerprint)
  }

  test("golden: the frozen sidecar schema of V5 and V7 files keeps its fingerprint") {
    assertEquals(hex(SnapshotSidecarV7.fingerprint), GoldenPreviousSidecarFingerprint)
  }

  test("golden: the frozen sidecar schema of V8 files keeps its fingerprint") {
    assertEquals(hex(SnapshotSidecarV8.fingerprint), GoldenV8SidecarFingerprint)
  }

  test("golden: the schema fingerprint and encodings of the V7 checkpoint binding") {
    assertEquals(hex(CheckpointBinding.fingerprint), GoldenBindingFingerprint)
    def encoded(b: CheckpointBinding) = hex(Codec.encode(b)(using CheckpointBinding.codec))
    assertEquals(encoded(CheckpointBinding.Legacy), "00")
    assertEquals(encoded(CheckpointBinding.Unbacked), "01")
    assertEquals(
      encoded(
        CheckpointBinding.Bound(
          xyz.kd5ujc.scarab.ledger.UtxoKeyLayout.TxIdIndex16,
          xyz.kd5ujc.scarab.codec.Bytes.wrap(Array.tabulate[Byte](32)(_.toByte))
        )
      ),
      "020020" + (0 until 32).map(i => f"$i%02x").mkString
    )
  }

  // V10 (#826) records the reference build in its header, outside the fingerprinted binding and
  // sidecar schemas, whose goldens above are unchanged from V9: a length byte and the flag string
  test("golden: the reference build a V10 file records after its magic, before the binding") {
    assertEquals(new String(SnapshotV10.Magic, "US-ASCII"), "SCRBSNPA")
    assertEquals(hex(SnapshotV10.buildBytes(ReferenceBuild.Release11_1)), "0431312e31")
    assertEquals(hex(SnapshotV10.buildBytes(ReferenceBuild.Release11_2)), "0431312e32")
    assertEquals(
      hex(SnapshotV10.buildBytes(ReferenceBuild.Leios)),
      "106c65696f732d72632d32303236773437"
    )
  }

  test("golden: the sidecar of the initial state of the eras fixture encodes to a fixed digest") {
    val (pending, extras) = (initial.extras, initial.ext.ledgerState.state) match
      case (MirrorExtras.InByron(pending, follow, protocol), EraLedgerState.ByronCurrent(b)) =>
        (pending, ConsensusExtras(follow, b, protocol))
      case _ => fail("the initial state is not Byron")
    val sidecar = SnapshotSidecar(
      None,
      Map.empty,
      Set.empty,
      SnapshotSidecar.Chain.InByron(pending, extras)
    )
    val encoded = Codec.encode(sidecar)(using SnapshotSidecar.codec)
    val digest = java.security.MessageDigest.getInstance("SHA-256").digest(encoded)
    assertEquals(hex(digest) -> encoded.length, GoldenSidecar)
  }

  // A deliberate persisted-layout change needs a new FormatVersion, migration and previous-version
  // read test (ADR 0006). These are the native V5 writer's current sidecar goldens; complete-file
  // fixture hashes and their historical conversion provenance are pinned in SnapshotGoldenSuite.
  // Retired V1–V4 readers and fingerprints remain in pinned historical source and conversion evidence.
  // Last regenerated for the previous epoch nonce of PraosState, the PlutusV2 cost model that the
  // Alonzo genesis carries in `extraConfig` (part of the fixture's initial protocol parameters) and
  // the Byron ledger state (UTxO, delegation, update system) carried in NodeState, and the idle
  // pools of a stake snapshot (K1: the pool distribution lists a pool whose delegators have no stake).
  // and the installer metadata of the Byron update state's software proposals and application
  // versions (V8, #726), and the Dijkstra era among the eras and the fields Dijkstra adds to the
  // protocol parameters (V9, #54).
  // V10's sidecar was then changed in place, before any deployment, by the owner's decision of
  // 2026-11-14 (#988): the follow state in place of the bootstrap state, with no version bump; and
  // again by the owner's decision of 2026-11-16 (#85, #54): PlutusV4 among the kinds of script,
  // with no version bump; and again by the owner's decision of 2026-11-17 (#826): Praos2 among the
  // chain-dependent states, with no version bump.
  private val GoldenSidecarFingerprint = "5564943c2b843296"
  // The sidecar schema of V9 files (`SnapshotSidecarV9`), frozen at V10's change: never regenerated.
  private val GoldenV9SidecarFingerprint = "7650a759442924ec"
  // The sidecar schema of V5 and V7 files (`SnapshotSidecarV7`), frozen at V8: never regenerated.
  private val GoldenPreviousSidecarFingerprint = "7c483457b06802d6"
  // The sidecar schema of V8 files (`SnapshotSidecarV8`), frozen at V9: never regenerated.
  private val GoldenV8SidecarFingerprint = "31718eaf81232a77"
  // The V7 binding in front of the V5 body (#395): a change to it is a new version too.
  private val GoldenBindingFingerprint = "bd43aa08b4fae886"
  private val GoldenSidecar =
    ("404c9ad6acb477adf63fa1ca450c4e116761663b9dabe1f19f276f530933db5b", 913)
